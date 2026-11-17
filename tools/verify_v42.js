const fs=require('fs'),vm=require('vm'),path=require('path');
const projectRoot=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(projectRoot,'app/src/main/assets/index.html'),'utf8');
function extractObject(src, marker){
 const st=src.indexOf(marker); if(st<0) throw Error('marker missing '+marker);
 const brace=src.indexOf('{',st); let depth=0, quote=null, esc=false, line=false, block=false;
 for(let i=brace;i<src.length;i++){
  const c=src[i], n=src[i+1];
  if(line){if(c==='\n')line=false;continue;}
  if(block){if(c==='*'&&n==='/'){block=false;i++;}continue;}
  if(quote){if(esc){esc=false;continue;} if(c==='\\'){esc=true;continue;} if(c===quote)quote=null;continue;}
  if(c==='/'&&n==='/'){line=true;i++;continue;} if(c==='/'&&n==='*'){block=true;i++;continue;}
  if(c==='\''||c==='"'||c==='`'){quote=c;continue;}
  if(c==='{')depth++; if(c==='}'&&--depth===0)return src.slice(st,i+1)+';';
 }
 throw Error('unclosed object');
}
const context={
 TransactionType:{REFUND:'REFUND',INTERNAL_TRANSFER:'INTERNAL_TRANSFER',INCOME:'INCOME',EXPENSE:'EXPENSE'},
 roundMoney:n=>Math.round((Number(n)||0)*100)/100,
 getCat:id=>({name:'cat'}),getAcc:id=>({name:'acc'}),getMem:id=>({name:'member'}),
 state:{categories:[{id:'food'},{id:'shop'}],accounts:[],expenses:[],incomes:[]},console
};
vm.createContext(context);
vm.runInContext(extractObject(html,'var FinancialBreakdown ='),context);
const st={year:2026,monthIdx:10,categories:[{id:'food'},{id:'shop'}],accounts:[],
 expenses:[
  {year:'2026',monthIdx:'10',categoryId:'food',amount:'100'},
  {year:2026,monthIdx:10,categoryId:'food',amount:50},
  {year:2026,monthIdx:10,categoryId:'shop',amount:25,id:'x'},
  {year:2026,monthIdx:10,categoryId:'shop',amount:25,id:'x'},
  {year:2026,monthIdx:10,categoryId:'food',amount:20,txType:'REFUND',id:'refund'},
  {year:2026,monthIdx:10,categoryId:'shop',amount:500,txType:'INTERNAL_TRANSFER',id:'transfer'},
  {year:2026,monthIdx:9,categoryId:'food',amount:900,id:'prev'}
 ],incomes:[{year:'2026',monthIdx:'10',amount:'300'}]};
const fb=context.FinancialBreakdown.compute(st,2026,10);
if(fb.grossExpenses!==175 || fb.netExpenses!==155 || fb.totalRefunds!==20 || fb.internalTransfers!==500 || fb.totalIncome!==300 || fb.categoryTotals.food!==130 || fb.categoryTotals.shop!==25 || fb.dedupedTransactions.length!==5) throw Error('FinancialBreakdown assertion failed '+JSON.stringify(fb));
// Verify legacy records without IDs survive migration, while explicit duplicate IDs collapse.
const fnStart=html.indexOf('function deduplicateStateExpensesInPlace()');
let depth=0, begin=html.indexOf('{',fnStart), end=-1, quote=null, esc=false;
for(let i=begin;i<html.length;i++){const c=html[i];if(quote){if(esc){esc=false;continue;}if(c==='\\'){esc=true;continue;}if(c===quote)quote=null;continue;}if(c==='\"'||c==="'"||c==='`'){quote=c;continue;}if(c==='{')depth++;else if(c==='}'&&--depth===0){end=i+1;break;}}
const dedupeContext={state:{expenses:[{amount:1},{amount:2},{amount:3,id:'same'},{amount:4,id:'same'}]},Date,console};vm.createContext(dedupeContext);vm.runInContext(html.slice(fnStart,end),dedupeContext);vm.runInContext('deduplicateStateExpensesInPlace()',dedupeContext);
if(dedupeContext.state.expenses.length!==3 || !dedupeContext.state.expenses[0].id || !dedupeContext.state.expenses[1].id) throw Error('Legacy dedupe migration assertion failed');
// v42: verify one authoritative render base and the repaired final UI/runtime layers.
for (const legacy of ['_renderAllV11','_renderAllV13','_renderAllV15','_renderAllV102','_renderAllV103','_renderAllV107']) {
 if (html.includes(legacy)) throw Error('Historical render wrapper still present: '+legacy);
}
if (!html.includes('const _origMasterRenderAll = _renderAllBase;')) throw Error('Canonical renderAll base is not active');
if (html.includes('masarifak-v37-ledger-consistency')) throw Error('Redundant v37 render wrapper still present');
if (!html.includes('homeDonutTopCatTitle')) throw Error('Top category label integration missing');
if (html.includes('__msf_ota_eof_sentinel__')) throw Error('Destructive OTA sentinel cleanup remains');
if (html.includes('new Function(flags.patchJs)')) throw Error('Remote executable JavaScript patch path remains');
if (html.includes('var(--accent) !important')) throw Error('Undefined accent variable still affects settings');
if (!html.includes('masarifak-v42-clean-layout-contract')) throw Error('Final v42 layout contract missing');
if (!html.includes('onclick="openExpenseDetailsSheet(\'${eid}\')" class="expense-entry-card"')) throw Error('Transaction cards do not open the details sheet');
if (!html.includes("cat_subscriptions: 'icon_obligations'")) throw Error('Extended category icon mapping missing');
if (html.includes("سجّل الدخول بحساب Google أولاً لفحص التحديثات.")) throw Error('OTA check still requires Google sign-in');
// Verify the running app version and preserve the Google auth callback.
if (!html.includes("window.getMsfCurrentVersion = function()")) throw Error('OTA version resolver missing');
if (!html.includes("'42.0.0'")) throw Error('v42 runtime version fallback missing');
// Parse every inline JavaScript block with Node's parser.
const scripts=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).filter(x=>x.trim());
for(let i=0;i<scripts.length;i++) new vm.Script(scripts[i],{filename:'inline-script-'+i});
// Structural duplicate checks.
const markupOnly=html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi,'');
const ids=[...markupOnly.matchAll(/\bid=["']([^"']+)["']/g)].map(m=>m[1]);
const idCounts=ids.reduce((a,x)=>(a[x]=(a[x]||0)+1,a),{});
const dupIds=Object.entries(idCounts).filter(([k,v])=>v>1);
if(dupIds.length) throw Error('Duplicate static HTML IDs: '+JSON.stringify(dupIds.slice(0,20)));
const fnNames=[...html.matchAll(/\bfunction\s+([\w$]+)\s*\(/g)].map(m=>m[1]);
const fnCounts=fnNames.reduce((a,x)=>(a[x]=(a[x]||0)+1,a),{});
const dupFns=Object.entries(fnCounts).filter(([k,v])=>v>1);
console.log(JSON.stringify({scripts:scripts.length,htmlIds:ids.length,duplicateIds:dupIds.length,functionDeclarations:fnNames.length,duplicateFunctionDeclarations:dupFns.length,financialBreakdown:'PASS',testValues:{grossExpenses:fb.grossExpenses,netExpenses:fb.netExpenses,totalRefunds:fb.totalRefunds,internalTransfers:fb.internalTransfers,totalIncome:fb.totalIncome,categoryTotals:fb.categoryTotals}},null,2));
