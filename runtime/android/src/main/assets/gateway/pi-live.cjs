'use strict';
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawn}=require('node:child_process');

// The private runtime config contains only the authenticated loopback route.
// User skills and sessions stay in HOME; no upstream credential reaches Pi.
async function runPi(executable,args,environment) {
  if (!environment.HOME || !path.isAbsolute(environment.HOME)) throw Error('Pi HOME is required');
  const config=JSON.parse(environment.MOBBY_PI_CONFIG);
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'mobby-pi-'));
  const env={...environment,PI_CODING_AGENT_DIR:directory};
  delete env.MOBBY_PI_CONFIG;
  const forward=signal=>child?.kill(signal);
  let child;
  try {
    fs.writeFileSync(path.join(directory,'models.json'),JSON.stringify(config),{mode:0o600});
    fs.writeFileSync(path.join(directory,'settings.json'),JSON.stringify({
      transport:'sse',cacheWarming:'off',images:{autoResize:false},
      defaultProjectTrust:'never',packages:[],retry:{enabled:true,maxRetries:2},
      compaction:{reserveTokens:config.providers.mobby.models[0].maxTokens,keepRecentTokens:16384}
    }),{mode:0o600});
    const root=path.join(environment.HOME,'.pi','agent');
    const sessions=path.join(root,'sessions');
    fs.mkdirSync(sessions,{recursive:true});
    const skillRoots=[path.join(root,'skills'),path.join(process.cwd(),'.pi','skills')];
    const launchArgs=[...args,'--session-dir',sessions,'--no-extensions','--no-prompt-templates','--no-themes','--no-skills'];
    for(const skills of skillRoots)if(fs.existsSync(skills))launchArgs.push('--skill',skills);
    child=spawn(executable,launchArgs,{env,stdio:'inherit'});
    const onTerm=()=>forward('SIGTERM'),onInt=()=>forward('SIGINT');
    process.on('SIGTERM',onTerm);process.on('SIGINT',onInt);
    try {
      return await new Promise((resolve,reject)=>{
        child.once('error',reject);
        child.once('exit',(code,signal)=>resolve(code ?? (signal==='SIGINT'?130:143)));
      });
    } finally {process.removeListener('SIGTERM',onTerm);process.removeListener('SIGINT',onInt);}
  } finally {fs.rmSync(directory,{recursive:true,force:true});}
}
module.exports={runPi};
