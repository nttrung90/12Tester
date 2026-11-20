import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {connect,setup,status,createApp,NEW_OWNER,OWNER} from './independent-fixture.mjs';
const db=await connect();const results=[];
try {
  for(const [name,uid,slot,expected] of [['Existing owner second app receives quota immediately',OWNER,2,24],['Registration-day owner first app receives quota immediately',NEW_OWNER,1,12]]) {
    await db.query('begin');
    try {
      await setup(db);const before=await status(db,uid);await createApp(db,{uid,slot});const after=await status(db,uid);
      const correct=after.base_required_tests===expected;
      results.push({name,expectedBase:expected,before,after,correct});console.log(correct?'PASS:':'REPRODUCED:',name,JSON.stringify({expected,actual:after.base_required_tests}));
      assert.equal(correct,false,'This baseline-only probe expects the pre-fix defect; do not use it as final acceptance');
    }finally{await db.query('rollback');}
  }
}finally{writeFileSync(new URL('./baseline-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');await db.end();}
