package chester.tyck

import cats.implicits.*
import chester.error.*
import chester.parser.ParserSource
import chester.resolve.{SimpleDesalt, resolveOpSeq}
import chester.syntax.Name
import chester.syntax.concrete.*
import chester.syntax.core.*
import chester.tyck.*
import chester.utils.*
import chester.utils.propagator.*
import chester.syntax.*
import chester.tyck.api.{NoopSemanticCollector, SemanticCollector, UnusedVariableWarningWrapper}

import scala.language.implicitConversions
import scala.util.boundary
import scala.util.boundary.break

type Tyck = Get[TyckProblem, Unit]

trait Elaborater extends ProvideCtx with ElaboraterCommon {

  def checkType(expr: Expr)(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): Term = {
    // Create a new type cell representing the kind Typeω (the type of types)
    val kindType = literal(Typeω: Term)

    elab(expr, kindType, toEffectsCell(NoEffect))
  }

  def checkTypeId(expr: Expr)(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): CellId[Term] = {
    toId(checkType(expr))
  }

  def elabTy(expr: Option[Expr])(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): Term =
    expr match {
      case Some(expr) => checkType(expr)
      case None => Meta(newType)
    }

  def elab(expr: Expr, ty: CellIdOr[Term], effects: CIdOf[EffectsCell])(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): Term

  def elabId(expr: Expr, ty: CellIdOr[Term], effects: CIdOf[EffectsCell])(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): CellId[Term] = {
    val term = elab(expr, ty, effects)
    toId(term)
  }
}

trait ProvideElaborater extends ProvideCtx with Elaborater with ElaboraterFunction with ElaboraterFunctionCall {

  // TODO: add something for implicit conversion

  def newSubtype(ty: CellIdOr[Term], cause: Expr)(using localCtx: LocalCtx, ck: Tyck, state: StateAbility[Tyck]): CellId[Term] = {
    val cell = newType
    state.addPropagator(Unify(toId(ty), cell, cause))
    cell
  }

  def elabBlock(expr: Block, ty0: CellIdOr[Term], effects: CIdOf[EffectsCell])(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): BlockTerm = {
    val ty = toId(readMetaVar(toTerm(ty0)))
    val Block(heads0, tail, meta) = expr
    val heads = heads0.map(resolve)
    {
      case class DefInfo(expr: LetDefStmt, id: UniqIdOf[LocalV], tyAndVal: TyAndVal, item: ContextItem)

      val defs = heads.collect {
        case expr: LetDefStmt if expr.kind == LetDefType.Def =>
          val name = expr.defined match {
            // TODO: support other defined patterns
            case DefinedPattern(PatternBind(name, _)) => name.name
          }
          val tyandval = TyAndVal.create()
          val id = UniqId.generate[LocalV]
          val localv = newLocalv(name, tyandval.ty, id, meta)
          val r = parameter.newSymbol(localv, id, expr)
          DefInfo(expr, UniqId.generate[LocalV], tyandval, ContextItem(name, id, localv, tyandval.ty, Some(r)))
      }
      val defsMap = defs.map(info => (info.expr, info)).toMap
      var ctx = localCtx.add(defs.map(_.item))
      val names = heads.collect {
        case expr: LetDefStmt => expr.defined match {
          case DefinedPattern(PatternBind(name, _)) => name
        }
      }
      if (names.hasDuplication) {
        val problem = DuplicateDefinition(expr)
        ck.reporter.apply(problem)
      }
      val stmts: Seq[StmtTerm] = heads.flatMapOrdered {
        case expr: LetDefStmt if expr.kind == LetDefType.Def => {
          implicit val localCtx: LocalCtx = ctx
          val d = defsMap.apply(expr)
          val ty = expr.ty match {
            case Some(tyExpr) => {
              val t = checkTypeId(tyExpr)
              merge(t, d.tyAndVal.tyId)
              t
            }
            case None => d.tyAndVal.ty
          }
          val wellTyped = elabId(expr.body.get, ty, effects)
          merge(d.tyAndVal.valueId, wellTyped)
          ctx = ctx.knownAdd(d.id, TyAndVal(ty, wellTyped))
          Vector(DefStmtTerm(d.item.name, Meta(wellTyped), toTerm(ty)))
        }
        case expr: LetDefStmt if expr.kind == LetDefType.Let => {
          implicit val localCtx: LocalCtx = ctx
          val name = expr.defined match {
            // TODO: support other defined patterns
            case DefinedPattern(PatternBind(name, _)) => name.name
          }
          val id = UniqId.generate[LocalV]
          val ty = expr.ty match {
            case Some(tyExpr) => checkType(tyExpr)
            case None => newTypeTerm
          }
          val localv = newLocalv(name, ty, id, meta)
          val r = parameter.newSymbol(localv, id, expr)
          val wellTyped = elab(expr.body.get, ty, effects)
          ctx = ctx.add(ContextItem(name, id, localv, ty, Some(r))).knownAdd(id, TyAndVal(ty, wellTyped))
          Vector(LetStmtTerm(name, wellTyped, ty))
        }
        case importStmt: ImportStmt => {
          ck.reporter.apply(NotImplemented(importStmt))
          Vector()
        }
        case expr => {
          implicit val localCtx: LocalCtx = ctx
          val ty = newType
          Vector(ExprStmtTerm(elab(expr, ty, effects), Meta(ty)))
        }
      }
      {
        implicit val localCtx: LocalCtx = ctx
        val tailExpr = tail.getOrElse(UnitExpr(meta))
        val wellTyped = elab(tailExpr, ty, effects)
        BlockTerm(stmts, wellTyped)
      }
    }
  }

  /** ty is lhs */
  override def elab(expr: Expr, ty0: CellIdOr[Term], effects: CIdOf[EffectsCell])(using localCtx: LocalCtx, parameter: SemanticCollector, ck: Tyck, state: StateAbility[Tyck]): Term = toTerm {
    val ty = toId(readMetaVar(toTerm(ty0)))
    resolve(expr) match {
      case expr@Identifier(name, meta) => {
        localCtx.get(name) match {
          case Some(c: ContextItem) => {
            if (c.reference.isDefined) {
              c.reference.get.referencedOn(expr)
            }
            state.addPropagator(Unify(ty, c.tyId, expr))
            c.ref
          }
          case None => {
            val problem = UnboundVariable(name, expr)
            ck.reporter.apply(problem)
            ErrorTerm(problem)
          }
        }
      }
      case expr@IntegerLiteral(value, meta) => {
        state.addPropagator(LiteralType(expr, ty))
        AbstractIntTerm.from(value, convertMeta(meta))
      }
      case expr@RationalLiteral(value, meta) => {
        state.addPropagator(LiteralType(expr, ty))
        RationalTerm(value, convertMeta(meta))
      }
      case expr@StringLiteral(value, meta) => {
        state.addPropagator(LiteralType(expr, ty))
        StringTerm(value, convertMeta(meta))
      }
      case expr@SymbolLiteral(value, meta) => {
        state.addPropagator(LiteralType(expr, ty))
        SymbolTerm(value, convertMeta(meta))
      }
      case expr@UnitExpr(meta) => {
        unify(ty, UnitType(convertMeta(meta)), expr)
        UnitTerm(convertMeta(meta))
      }
      case expr@ListExpr(terms, meta) => {
        val t = newType
        // Relate the list type 'ty' to 'ListType(t)'
        state.addPropagator(ListOf(t, ty, expr))

        // For each term, check it with its own type variable and collect the results
        val termResults = terms.map { term =>
          val elemTy = newType
          val wellTypedTerm = elab(term, elemTy, effects)
          (wellTypedTerm, elemTy)
        }

        // Collect the types of the elements
        val elemTypes = termResults.map(_._2).toVector

        // Ensure that 't' is the union of the element types
        if (elemTypes.nonEmpty) state.addPropagator(UnionOf(t, elemTypes, expr))

        ListTerm(termResults.map(_._1), convertMeta(meta))
      }
      case expr@TypeAnotationNoEffects(innerExpr, tyExpr, meta) =>
        // Check the type annotation expression to get its type
        val declaredTyTerm = checkType(tyExpr)

        unify(ty, declaredTyTerm, expr)

        elab(innerExpr, declaredTyTerm, effects)
      case expr: FunctionExpr => elabFunction(expr, ty, effects)
      case expr: Block => elabBlock(expr, ty, effects)
      case expr: DesaltFunctionCall => elabFunctionCall(expr, ty, effects)
      case expr@ObjectExpr(fields, meta) =>
        elabObjectExpr(expr, fields, ty, effects)
      case expr: Expr => {
        val problem = NotImplemented(expr)
        ck.reporter.apply(problem)
        ErrorTerm(problem)
      }
    }
  }

  given ckToReport(using ck: Tyck): Reporter[TyckProblem] = ck.reporter

  // TODO: untested
  def elabObjectExpr(
                      expr: ObjectExpr,
                      fields: Vector[ObjectClause],
                      ty: CellId[Term],
                      effects: CIdOf[EffectsCell]
                    )(using
                      localCtx: LocalCtx,
                      parameter: SemanticCollector,
                      ck: Tyck,
                      state: StateAbility[Tyck]
                    ): Term = {
    // Create collections to store field keys and types
    val fieldTypeVars = scala.collection.mutable.Map[Term, CellId[Term]]()
    val elaboratedFields = fields.flatMap {
      case ObjectExprClauseOnValue(keyExpr, valueExpr) =>
        // Elaborate the key and value expressions
        val elaboratedKey = elab(keyExpr, newType, effects)
        val fieldType = newType
        val elaboratedValue = elab(valueExpr, fieldType, effects)
        fieldTypeVars += (elaboratedKey -> fieldType)
        Some(ObjectClauseValueTerm(elaboratedKey, elaboratedValue))
      // Handle other possible clauses
      case _ => ???
    }

    // Construct the object term with elaborated fields
    val objectTerm = ObjectTerm(elaboratedFields)

    // Construct the expected object type
    val expectedObjectType = ObjectType(elaboratedFields.map {
      case ObjectClauseValueTerm(keyTerm, _, _) =>
        ObjectClauseValueTerm(keyTerm, Meta(fieldTypeVars(keyTerm)))
    })

    // Unify the expected type with the object's type
    unify(ty, expectedObjectType, expr)

    objectTerm
  }
}

trait DefaultImpl extends ProvideElaborater with ProvideImpl with ProvideElaboraterFunction with ProvideElaboraterFunctionCall {

  def check(expr: Expr, ty: Option[Term] = None, effects: Option[Effects] = None, sementicCollector: SemanticCollector = NoopSemanticCollector): TyckResult[Unit, Judge] = {
    implicit val collecter: UnusedVariableWarningWrapper = new UnusedVariableWarningWrapper(sementicCollector)
    val reporter = new VectorReporter[TyckProblem]
    implicit val get: Tyck = new Get(reporter, new MutBox(()))
    implicit val able: StateAbility[Tyck] = stateAbilityImpl
    val ty1: CellId[Term] = ty match {
      case Some(ty) => {
        val cell = literal[Term](ty)
        cell
      }
      case None => {
        val cell = newType
        cell
      }
    }
    val effects1: CIdOf[EffectsCell] = effects match {
      case Some(effects) => {
        val cell = toEffectsCell(effects)
        cell
      }
      case None => {
        newEffects
      }
    }
    implicit val ctx: LocalCtx = LocalCtx.default
    val wellTyped = elabId(expr, ty1, effects1)
    able.naiveZonk(Vector(ty1, effects1, wellTyped))
    val judge = Judge(able.readStable(wellTyped).get, able.readStable(ty1).get, able.readUnstable(effects1).get)
    val finalJudge = finalizeJudge(judge)

    TyckResult0((), finalJudge, reporter.getReports)

  }

  def finalizeJudge(
                     judge0: Judge
                   )(using ck: Tyck,
                     able: StateAbility[Tyck],
                     recording: SemanticCollector,
                     reporter: Reporter[TyckProblem]): Judge = {
    var judge = judge0
    boundary {
      while (true) {
        val metas = judge.collectMeta
        if (metas.isEmpty) break()
        able.naiveZonk(metas.map(x => x.unsafeRead[CellId[Term]]))
        judge = judge.replaceMeta(x => able.readUnstable(x.unsafeRead[CellId[Term]]).get)
      }
    }
    judge
  }

  def checkTop(fileName: String, expr: Expr, reporter0: Reporter[Problem], sementicCollector: SemanticCollector = NoopSemanticCollector): chester.syntax.TAST = {
    implicit val collecter: UnusedVariableWarningWrapper = new UnusedVariableWarningWrapper(sementicCollector)
    implicit val reporter: ReporterTrackError[Problem] = new ReporterTrackError(reporter0)
    implicit val get: Tyck = new Get(reporter, new MutBox(()))
    implicit val able: StateAbility[Tyck] = stateAbilityImpl
    implicit var ctx: LocalCtx = LocalCtx.default
    val (module, block): (ModuleRef, Block) = resolve(expr) match {
      case b@Block(head +: heads, tail, meta) => resolve(head) match {
        case ModuleStmt(module, meta) => (module, Block(heads, tail, meta))
        case stmt => (DefaultModule, b)
      }
      case expr => (DefaultModule, Block(Vector(), Some(expr), expr.meta))
    }
    ctx = ctx.updateModule(module)
    val ty = newType
    val effects = newEffects
    val wellTyped = elabBlock(block, ty, effects)
    able.naiveZonk(Vector(ty, effects))
    val judge = Judge(wellTyped, able.readStable(ty).get, able.readUnstable(effects).get)
    val finalJudge= finalizeJudge(judge)

    TAST(
      fileName = fileName,
      module = module,
      ast = finalJudge.wellTyped.asInstanceOf[BlockTerm],
      ty = finalJudge.ty,
      effects = finalJudge.effects,
      problems = reporter.getSeverityMap
    )
  }
}

object Tycker extends DefaultImpl with ProvideMutable {
}

export Tycker.{check, checkTop}