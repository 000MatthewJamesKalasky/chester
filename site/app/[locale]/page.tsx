'use client';

import { useEffect, useRef } from "react";
import { XTerm } from "@pablo-lion/xterm-react";
import { startRepl, startReplPty, startReplReadline } from "../../generated/main.js";
import "../../types/main.d.ts";
import { Terminal } from '@xterm/xterm';
import { Readline } from "xterm-readline";
import { useTranslations } from 'next-intl';

export default function Home() {
  const t = useTranslations('Home');
  const xtermRef = useRef<any>(null);

  useEffect(() => {
    // https://stackoverflow.com/questions/66096260/why-am-i-getting-referenceerror-self-is-not-defined-when-i-import-a-client-side/66100185#66100185
    const initTerminal = async () => {
      if (xtermRef.current) {
        const terminal = xtermRef.current.terminal as Terminal;
        const { WebLinksAddon } = await import('@xterm/addon-web-links');
        terminal.loadAddon(new WebLinksAddon());
        const { FitAddon } = await import('@xterm/addon-fit');
        const fitAddon = new FitAddon();
        terminal.loadAddon(fitAddon);
        fitAddon.fit();
        if (false) {
          startRepl(terminal);
        }else if(false){
          // @ts-expect-error xterm-pty types are not recognized
          const {openpty} = await import('xterm-pty');
          const { master, slave } = openpty();
          terminal.loadAddon(master);
          startReplPty(slave);
        } else {
          const rl = new Readline();
          terminal.loadAddon(rl);
          startReplReadline(rl);
        }
        terminal.focus();
      }
    };
    initTerminal();
  }, []);

  return (
    <div className="flex flex-col min-h-screen bg-white dark:bg-gray-900 text-black dark:text-white">
      <div className="flex-grow flex flex-col items-center justify-center p-4 pb-8 gap-8 sm:p-8">
        <main className="flex flex-col gap-6 w-full max-w-4xl">
          <h1 className="text-2xl font-bold text-center">{t('title')}</h1>
          <div className="w-full h-[50vh] sm:h-[70vh] bg-black rounded">
            <XTerm ref={xtermRef} />
          </div>
        </main>
      </div>
    </div>
  );
}
