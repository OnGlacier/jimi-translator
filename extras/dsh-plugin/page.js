/**
 * 独立翻译页：/rl-translator
 *
 * 为什么要有它：DSH 聊天页里那张卡片只适合「顺手用」。真正当阅读器用时，用户想要一个
 * 只干翻译、没有任何会话界面的窗口——把它用手机浏览器打开（http://127.0.0.1:3080/rl-translator），
 * 再用 ColorOS 的「应用浮窗」浮在 Zotero 上面，就是一个独立的翻译浮窗。
 *
 * 页面只依赖同源的 /dsh-rl-translator（不走 /api，不需要 DSH 的登录 Cookie），
 * 所以系统浏览器直接能打开。
 */
export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b1017">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<title>RL 学术翻译</title>
<style>
:root{color-scheme:dark light}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font:400 15px/1.6 -apple-system,"PingFang SC","Noto Sans SC","HarmonyOS Sans SC",system-ui,sans-serif;background:#0b1017;color:#e6edf3}
body.light{background:#fbfbfa;color:#1c1f24}
header{position:sticky;top:0;z-index:5;background:rgba(11,16,23,.93);backdrop-filter:blur(14px);border-bottom:1px solid #1f2733;padding:10px 12px 9px}
body.light header{background:rgba(251,251,250,.94);border-bottom-color:#e6e4de}
.row{display:flex;align-items:center;gap:8px}
.title{font-size:14px;font-weight:600;letter-spacing:.02em}
.spacer{flex:1}
.dot{width:8px;height:8px;border-radius:50%;background:#6b7280;flex:none}
.dot.on{background:#22c55e;animation:pulse 1.6s infinite}
.dot.err{background:#ef4444}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
.status{font-size:12px;color:#8b98a9}
body.light .status{color:#6b7280}
button{font:inherit;color:inherit;background:transparent;border:1px solid #2a333f;border-radius:10px;padding:7px 12px;font-size:13px}
body.light button{border-color:#dcd9d2}
button:active{transform:scale(.97)}
button.primary{background:#3b82f6;border-color:transparent;color:#fff}
button.on{background:#3b82f6;border-color:transparent;color:#fff}
.toolbar{display:flex;gap:8px;margin-top:9px;flex-wrap:wrap}
main{padding:14px 14px 92px}
.item{border-left:2px solid #3b82f6;padding:2px 0 2px 11px;margin:0 0 18px}
.zh{font-size:17px;line-height:1.78;word-break:break-word}
.en{margin-top:6px;font-size:12.5px;line-height:1.55;color:#93a1b3;display:none;word-break:break-word}
.en.show{display:block}
body.light .en{color:#6b7280}
.meta{margin-top:5px;display:flex;gap:12px;font-size:11px;color:#7c8899}
body.light .meta{color:#8a8f98}
.mini{border:none;padding:0;font-size:11px;color:#7c8899;text-decoration:underline;background:none}
.empty{padding:44px 12px;text-align:center;color:#7c8899;font-size:13.5px;line-height:2}
footer{position:fixed;left:0;right:0;bottom:0;padding:8px 14px calc(8px + env(safe-area-inset-bottom));font-size:11.5px;color:#7c8899;background:linear-gradient(to top,rgba(11,16,23,.98),rgba(11,16,23,.55));pointer-events:none}
body.light footer{background:linear-gradient(to top,#fbfbfa,rgba(251,251,250,.55))}
.warn{color:#f59e0b}
.err{color:#ef4444}
</style>
</head>
<body>
<header>
  <div class="row">
    <span class="title">RL 学术翻译</span>
    <span class="spacer"></span>
    <span class="dot" id="dot"></span>
    <span class="status" id="status">连接中…</span>
  </div>
  <div class="toolbar">
    <button id="go" class="primary">译一屏</button>
    <button id="auto">自动跟读</button>
    <button id="clear">清空</button>
    <span class="spacer"></span>
    <button id="theme">◐</button>
  </div>
  <div class="status" id="hint" style="margin-top:8px;min-height:16px"></div>
</header>
<main id="feed"><div class="empty">正在连接 Host…</div></main>
<footer id="foot"></footer>
<script>
(function(){
  var BASE='/dsh-rl-translator';
  var S=null, renderedKey='', openSet={}, pending=false;
  function $(id){return document.getElementById(id)}
  function pad(n){return n<10?('0'+n):(''+n)}
  function clock(ts){if(!ts)return '—';var d=new Date(ts);return pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds())}
  function esc(v){return String(v==null?'':v).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
  function api(path,body){
    var init=body?{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)}:undefined;
    return fetch(BASE+path,init).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()})
  }
  function control(action,extra){
    if(pending)return;
    pending=true;
    if(action==='now')$('go').textContent='读取中…';
    var body={action:action};
    if(extra)for(var k in extra)body[k]=extra[k];
    api('/control',body).then(function(res){if(res&&res.state)S=res.state;pending=false;$('go').textContent='译一屏';render()}).catch(function(e){pending=false;$('go').textContent='译一屏';hint('命令失败：'+e.message,true)})
  }
  function hint(text,isErr){
    var el=$('hint');
    el.className='status'+(isErr?' err':' warn');
    el.textContent=text||'';
  }
  function fmtEntries(entries){
    var html='';
    for(var i=0;i<entries.length;i++){
      var e=entries[i];
      var open=openSet[e.id]?' show':'';
      html+='<div class="item" data-id="'+e.id+'">'
        +'<div class="zh">'+esc(e.zh)+'</div>'
        +'<div class="en'+open+'" data-en="'+e.id+'">'+esc(e.en)+'</div>'
        +'<div class="meta"><span>'+clock(e.at)+'</span><button class="mini" data-toggle="'+e.id+'">'+(open?'收起原文':'EN')+'</button></div>'
        +'</div>';
    }
    return html;
  }
  function render(){
    if(!S)return;
    var status=S.status||'idle';
    var running=S.config&&S.config.running;
    var map={idle:'待机',running:'自动跟读中',capturing:'读取屏幕…',thinking:'翻译中…',error:'需要处理'};
    $('dot').className='dot'+(status==='error'?' err':(running||status==='capturing'||status==='thinking')?' on':'');
    $('status').textContent=map[status]||status;
    $('auto').textContent=running?'自动跟读中':'自动跟读';
    $('auto').className=running?'on':'';
    if(S.lastError)hint(S.lastError,true);
    else if(S.overlay&&S.overlay.hint)hint(S.overlay.hint,false);
    else if(S.lastEmptyAt&&(S.stats&&S.stats.segments))hint('这一屏没有新的英文正文',false);
    else hint('',false);
    var entries=(S.entries||[]);
    var key=entries.length+'|'+(entries.length?entries[entries.length-1].id:0);
    if(key!==renderedKey){
      var nearBottom=(window.innerHeight+window.scrollY)>=(document.body.scrollHeight-180);
      renderedKey=key;
      var feed=$('feed');
      if(!entries.length){
        feed.innerHTML='<div class="empty">点「译一屏」或打开「自动跟读」。<br>在 Zotero 里继续读、继续滚——这里只会出现还没有翻译过的段落。</div>';
      }else{
        feed.innerHTML=fmtEntries(entries);
      }
      if(nearBottom)window.scrollTo(0,document.body.scrollHeight);
    }
    var st=S.stats||{};
    $('foot').textContent='上次 '+clock(S.lastCaptureAt)+' · 新增 '+(st.segments||0)+' 段 · 跳过重复 '+(st.duplicates||0)+' · '+((S.model&&S.model.model)||'—');
  }
  function pull(){
    api('/state').then(function(next){S=next;render()}).catch(function(e){
      $('dot').className='dot err';
      $('status').textContent='连不上 Host';
      hint('连不上 Host：'+e.message,true);
    })
  }
  document.addEventListener('click',function(ev){
    var t=ev.target;
    if(t.id==='go'){control('now');return}
    if(t.id==='auto'){control((S&&S.config&&S.config.running)?'stop':'start');return}
    if(t.id==='clear'){control('clear');openSet={};renderedKey='';return}
    if(t.id==='theme'){document.body.classList.toggle('light');try{localStorage.setItem('rltr.page.theme',document.body.classList.contains('light')?'light':'dark')}catch(e){}return}
    var toggle=t.getAttribute&&t.getAttribute('data-toggle');
    if(toggle){
      var id=Number(toggle);
      openSet[id]=!openSet[id];
      var en=document.querySelector('[data-en="'+id+'"]');
      if(en)en.className='en'+(openSet[id]?' show':'');
      t.textContent=openSet[id]?'收起原文':'EN';
    }
  });
  try{if(localStorage.getItem('rltr.page.theme')==='light')document.body.classList.add('light')}catch(e){}
  pull();
  setInterval(pull,1500);
  document.addEventListener('visibilitychange',function(){if(!document.hidden)pull()});
})();
</script>
</body>
</html>`
