import assert from 'node:assert/strict';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import {spawn,spawnSync} from 'node:child_process';import {once} from 'node:events';import {fileURLToPath} from 'node:url';import test from 'node:test';
const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
for(const unlinked of [false,true])test(unlinked?'source-helper handoff waits after its old App executable is unlinked':'source-helper argument inspection fits the native bridge background-thread stack',{timeout:20000},async context=>{
 if(process.platform!=='darwin')return context.skip('native Darwin handoff');
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'mac-handoff-stack-'));let helper;
 try{
  const source=path.join(root,'source.c'),driver=path.join(root,'driver.c'),probe=path.join(root,'probe'),executable=path.join(root,'ProjectUpdater');
  fs.writeFileSync(source,'#include <stdio.h>\n#include <unistd.h>\nint main(void){puts("ready");fflush(stdout);sleep(2);return 0;}\n');
  fs.writeFileSync(driver,'#include <pthread.h>\n#include <stdint.h>\n#include <stdio.h>\n#include "BridgeHandoff.h"\nstatic void *check(void *target){return (void *)(intptr_t)logseq_bridge_wait_for_source_helper(target);}\nint main(int argc,char **argv){if(argc!=2)return 2;pthread_attr_t attributes;pthread_attr_init(&attributes);pthread_attr_setstacksize(&attributes,512*1024);pthread_t thread;int status=pthread_create(&thread,&attributes,check,argv[1]);pthread_attr_destroy(&attributes);if(status)return status;void *result;pthread_join(thread,&result);printf("handoff-result=%ld\\n",(long)(intptr_t)result);return result?1:0;}\n');
  const compile=args=>{const r=spawnSync('xcrun',['clang','-O2','-Wall','-Wextra','-Werror',...args],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,r.stderr);};
  compile([source,'-o',executable]);compile(['-I',path.join(repo,'resources/macos-project-updater'),driver,path.join(repo,'resources/macos-project-updater/BridgeHandoff.c'),'-o',probe]);
  helper=spawn(executable,['--target',path.join(root,'Logseq.app')],{stdio:['ignore','pipe','ignore']});const closed=once(helper,'close');
  await once(helper.stdout,'data');if(unlinked)fs.unlinkSync(executable);
  const result=spawnSync(probe,[path.join(root,'Logseq.app')],{encoding:'utf8',timeout:10000});
  assert.equal(result.signal,null);assert.equal(result.status,0,result.stderr);assert.equal(result.stdout,'handoff-result=0\n');await closed;
 }finally{
  if(helper&&helper.exitCode===null&&helper.signalCode===null){helper.kill('SIGTERM');await once(helper,'close');}
  fs.rmSync(root,{recursive:true,force:true});
 }
});
