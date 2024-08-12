package chester.utils

import java.io.File
import java.nio.file.{Files, Paths}

def readFileFrom(path: String): String = new String(Files.readAllBytes(Paths.get(path)))

def normalizeFilePath(path: String): String = Paths.get(path).getFileName.toString

def fileExists(path: String): Boolean = new File(path).exists()