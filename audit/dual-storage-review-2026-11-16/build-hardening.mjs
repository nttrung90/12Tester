import {readFileSync,writeFileSync} from 'node:fs';
const at=p=>new URL('../../'+p,import.meta.url);
// 087 is the last business core through 090. 091 accidentally removed business
// guards/reward fields. Restore its entire predecessor, with a media wrapper.
const original=readFileSync(at('supabase/migrations/087_campaign_tester_bonus_rounds.sql'),'utf8').replaceAll('\r\n','\n');
const name='CREATE OR REPLACE FUNCTION public._submit_tester_report_core(';
const start=original.indexOf(name);
const end=original.indexOf('$function$;',start)+'$function$;'.length;
if(start<0||end<start)throw Error('Missing canonical 087 business core');
const core=original.slice(start,end).replace(name,'CREATE OR REPLACE FUNCTION public._submit_tester_report_business_092(');
const head=readFileSync(new URL('hardening-head.sql',import.meta.url),'utf8');
const wrapper=readFileSync(new URL('hardening-submit-wrapper.sql',import.meta.url),'utf8');
const tail=readFileSync(new URL('hardening-tail.sql',import.meta.url),'utf8');
const mediaSource=readFileSync(at('supabase/migrations/091_dual_storage_report_screenshots.sql'),'utf8').replaceAll('\r\n','\n');
let access=readFileSync(new URL('hardening-access-helper.sql',import.meta.url),'utf8');
for(const fn of ['request_report_media_access','verify_media_access_grant_internal']) {
 const start=mediaSource.indexOf('CREATE OR REPLACE FUNCTION public.'+fn+'(');
 const end=mediaSource.indexOf('$function$;',start)+'$function$;'.length;
 if(start<0||end<start)throw Error('Missing access function '+fn);
 let body=mediaSource.slice(start,end);
 body=body.replace(/^\s*v_is_supabase := .*;$/gm,'');
 const action=fn==='request_report_media_access'?"RAISE EXCEPTION 'MEDIA_EXPIRED';":'RETURN false;';
 for(const row of ['attachment','report']) {
  const pattern=new RegExp('        IF v_'+row+'\\.media_expired = true[\\s\\S]*?        END IF;');
  if(!pattern.test(body))throw Error('Missing expiry block '+row);
  body=body.replace(pattern,"        IF public._report_media_deadline(p_report_id,p_attachment_id) IS NULL OR public._report_media_deadline(p_report_id,p_attachment_id)<=clock_timestamp() THEN "+action+' END IF;');
 }
 body=body.replace("v_expires_at := now() + interval '15 minutes';","v_expires_at := least(now() + interval '15 minutes',public._report_media_deadline(p_report_id,p_attachment_id));");
 body=body.replace('v_now_epoch > p_expires_at_epoch','v_now_epoch >= p_expires_at_epoch');
 const guard=fn==='request_report_media_access'?"IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=v_user_id AND NOT coalesce(is_locked,false)) THEN RAISE EXCEPTION 'PERMISSION_DENIED';END IF;":"IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND NOT coalesce(is_locked,false)) THEN RETURN false;END IF;";
 if(fn==='request_report_media_access') {
  const auth="RAISE EXCEPTION 'NOT_AUTHENTICATED';\n    END IF;";
  body=body.replace(auth,auth+'\n    '+guard);
 } else {
  body=body.replace('BEGIN\n','BEGIN\n    '+guard+'\n');
  const roleStart=body.indexOf('    IF v_report.user_id = p_user_id THEN');
  const roleEnd=body.indexOf('    IF v_role IS NULL THEN',roleStart);
  if(roleStart<0||roleEnd<0)throw Error('Missing grant role anchors');
  body=body.slice(0,roleStart)+`    SELECT * INTO v_camp FROM public.campaigns WHERE id=v_report.campaign_id;
    IF v_report.user_id=p_user_id THEN v_role:='author';
    ELSIF v_camp.owner_id=p_user_id THEN v_role:='owner';
    ELSIF EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND lower(coalesce(role,'')) IN('admin','moderator') AND NOT coalesce(is_locked,false)) THEN v_role:='admin';END IF;
`+body.slice(roleEnd);
 }
 access+='\n'+body+'\n';
}
writeFileSync(at('supabase/migrations/092_dual_storage_hardening.sql'),head+'\n'+core+'\n'+wrapper+'\n'+access+'\n'+tail);
console.log('Generated 092: complete pre-091 business core, private entry, atomic media wrapper.');
