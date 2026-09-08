package com.xetax.crm.agent.controller;

import com.xetax.crm.agent.service.AgentChatService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Visitor-facing endpoints — no auth, key-gated, permissive CORS (the widget
 * runs on other people's websites). Nothing here can reach CRM data.
 */
@RestController
@RequestMapping("/api/public/agents")
@RequiredArgsConstructor
public class PublicAgentController {

    private final AgentChatService chatService;

    @GetMapping("/{publicKey}/info")
    public ApiResponse<Map<String, Object>> info(@PathVariable String publicKey) {
        return ResponseUtil.success("Agent info", chatService.info(publicKey));
    }

    @PostMapping("/{publicKey}/chat")
    public ApiResponse<Map<String, Object>> chat(@PathVariable String publicKey,
                                                 @RequestBody Map<String, String> body,
                                                 HttpServletRequest request) {
        return ResponseUtil.success("Reply", chatService.chat(
                publicKey,
                request.getRemoteAddr(),
                body.get("sessionId"),
                body.get("message"),
                body));
    }

    /** The embeddable script — everything the visitor's page needs, self-contained. */
    /** Widget polls this only while a human (or the wait timer) is driving the chat. */
    @GetMapping("/{publicKey}/updates")
    public ApiResponse<Map<String, Object>> updates(@PathVariable String publicKey,
                                                    @RequestParam(required = false) String sessionId,
                                                    @RequestParam(required = false) Long after,
                                                    HttpServletRequest request) {
        return ResponseUtil.success("Updates", chatService.updates(publicKey, request.getRemoteAddr(), sessionId, after));
    }

    @GetMapping(value = "/{publicKey}/widget.js", produces = "application/javascript")
    public String widget(@PathVariable String publicKey, HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443
                    ? "" : ":" + request.getServerPort());
        return WIDGET_JS
                .replace("__KEY__", publicKey)
                .replace("__BASE__", base);
    }

    // language=JavaScript
    private static final String WIDGET_JS = """
(function(){
  var KEY='__KEY__', BASE='__BASE__';
  if(document.getElementById('xtx-agent-'+KEY))return;
  var sid;try{sid=localStorage.getItem('xtx-sid-'+KEY)||(Date.now().toString(36)+Math.random().toString(36).slice(2,10));localStorage.setItem('xtx-sid-'+KEY,sid);}catch(e){sid='s'+Date.now();}
  var color='#4f46e5', title='Assistant', welcome='Hi! How can I help?';

  var css=document.createElement('style');
  css.textContent='.xtx-btn{position:fixed;bottom:22px;right:22px;width:58px;height:58px;border-radius:50%;border:0;cursor:pointer;box-shadow:0 8px 30px rgba(0,0,0,.28);z-index:999999;display:flex;align-items:center;justify-content:center;transition:transform .2s}.xtx-btn:hover{transform:scale(1.07)}.xtx-btn svg{width:26px;height:26px;fill:#fff}.xtx-panel{position:fixed;bottom:92px;right:22px;width:360px;max-width:calc(100vw - 32px);height:520px;max-height:calc(100vh - 120px);background:#fff;border-radius:16px;box-shadow:0 18px 60px rgba(0,0,0,.3);z-index:999999;display:none;flex-direction:column;overflow:hidden;font-family:system-ui,-apple-system,Segoe UI,sans-serif}.xtx-panel.open{display:flex}.xtx-head{padding:14px 16px;color:#fff;font-weight:700;font-size:15px;display:flex;justify-content:space-between;align-items:center}.xtx-head button{background:none;border:0;color:#fff;font-size:18px;cursor:pointer;opacity:.85}.xtx-body{flex:1;overflow-y:auto;padding:14px;background:#f6f7fb;display:flex;flex-direction:column;gap:8px}.xtx-m{max-width:82%;padding:9px 12px;border-radius:14px;font-size:13.5px;line-height:1.45;white-space:pre-wrap;word-break:break-word}.xtx-m.bot{background:#fff;border:1px solid #e7e9f2;border-bottom-left-radius:4px;align-self:flex-start;color:#1f2437}.xtx-m.me{color:#fff;border-bottom-right-radius:4px;align-self:flex-end}.xtx-typing{align-self:flex-start;color:#8a90a8;font-size:12px;padding:2px 6px}.xtx-m.human{border-left:3px solid #22c55e}.xtx-who{font-size:10px;color:#8a90a8;margin:-4px 0 0 6px;align-self:flex-start}.xtx-m.sys{align-self:center;background:transparent;color:#8a90a8;font-size:11px;font-style:italic}.xtx-foot{display:flex;gap:8px;padding:10px;border-top:1px solid #eceef5;background:#fff}.xtx-foot input{flex:1;border:1px solid #dde0ec;border-radius:999px;padding:9px 14px;font-size:13.5px;outline:none}.xtx-foot input:focus{border-color:'+color+'}.xtx-send{border:0;border-radius:50%;width:38px;height:38px;cursor:pointer;color:#fff;display:flex;align-items:center;justify-content:center}.xtx-pow{font-size:10px;text-align:center;color:#a9aec4;padding:4px 0 7px;background:#fff}';
  document.head.appendChild(css);

  var btn=document.createElement('button');btn.className='xtx-btn';btn.id='xtx-agent-'+KEY;
  btn.innerHTML='<svg viewBox="0 0 24 24"><path d="M12 3C6.5 3 2 6.9 2 11.7c0 2.6 1.3 4.9 3.4 6.5-.1.9-.5 2.3-1.5 3.5 0 0 2.6-.3 4.6-1.7 1.1.3 2.3.5 3.5.5 5.5 0 10-3.9 10-8.8S17.5 3 12 3z"/></svg>';
  var panel=document.createElement('div');panel.className='xtx-panel';
  panel.innerHTML='<div class="xtx-head"><span class="xtx-title">Assistant</span><button aria-label="Close">\\u00d7</button></div><div class="xtx-body"></div><div class="xtx-foot"><input type="text" placeholder="Type a message\\u2026" maxlength="1500"/><button class="xtx-send"><svg width="16" height="16" viewBox="0 0 24 24" fill="#fff"><path d="M2 21l21-9L2 3v7l15 2-15 2v7z"/></svg></button></div><div class="xtx-pow">Powered by XetaX AI</div>';
  document.body.appendChild(btn);document.body.appendChild(panel);

  var body=panel.querySelector('.xtx-body'),input=panel.querySelector('input'),
      send=panel.querySelector('.xtx-send'),head=panel.querySelector('.xtx-head'),
      titleEl=panel.querySelector('.xtx-title'),closeBtn=head.querySelector('button');

  function paint(){btn.style.background=color;head.style.background=color;send.style.background=color;
    var mine=body.querySelectorAll('.xtx-m.me');for(var i=0;i<mine.length;i++)mine[i].style.background=color;}
  function add(text,who){var m=document.createElement('div');m.className='xtx-m '+who;m.textContent=text;
    if(who==='me')m.style.background=color;body.appendChild(m);body.scrollTop=body.scrollHeight;return m;}

  fetch(BASE+'/api/public/agents/'+KEY+'/info').then(function(r){return r.json();}).then(function(d){
    if(d&&d.data){color=d.data.themeColor||color;title=d.data.name||title;welcome=d.data.welcomeMessage||welcome;
      titleEl.textContent=title;paint();add(welcome,'bot');}
  }).catch(function(){});

  var busy=false,mode='ai',lastId=0,pollTimer=null,waitNote=null;
  function label(text){var l=document.createElement('div');l.className='xtx-who';l.textContent=text;body.appendChild(l);}
  function showUpdates(d){if(!d||!d.data)return;var msgs=d.data.messages||[];
    for(var i=0;i<msgs.length;i++){var m=msgs[i];if(m.id>lastId)lastId=m.id;
      if(m.role==='HUMAN'){if(waitNote){waitNote.remove();waitNote=null;}add(m.text,'bot human');}
      else if(m.role==='AI'){if(waitNote){waitNote.remove();waitNote=null;}add(m.text,'bot');}
      else if(m.role==='SYSTEM'){if(/joined/.test(m.text)){label(m.text);}}}
    setMode(d.data.mode||'ai');}
  function poll(){fetch(BASE+'/api/public/agents/'+KEY+'/updates?sessionId='+encodeURIComponent(sid)+'&after='+lastId)
    .then(function(r){return r.json();}).then(showUpdates).catch(function(){});}
  function setMode(m){if(m===mode)return;mode=m;
    if(m==='waiting'){if(!waitNote){waitNote=document.createElement('div');waitNote.className='xtx-typing';waitNote.textContent='Connecting you to a person\\u2026';body.appendChild(waitNote);body.scrollTop=body.scrollHeight;}}
    if(m!=='ai'){if(!pollTimer)pollTimer=setInterval(poll,3000);}
    else{if(pollTimer){clearInterval(pollTimer);pollTimer=null;}if(waitNote){waitNote.remove();waitNote=null;}}}
  function ask(){var q=input.value.trim();if(!q||busy)return;busy=true;input.value='';add(q,'me');
    var t=null;if(mode==='ai'){t=document.createElement('div');t.className='xtx-typing';t.textContent='typing\\u2026';
    body.appendChild(t);body.scrollTop=body.scrollHeight;}
    fetch(BASE+'/api/public/agents/'+KEY+'/chat',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({sessionId:sid,message:q})})
    .then(function(r){return r.json();})
    .then(function(d){if(t)t.remove();var data=d&&d.data||{};if(data.reply)add(data.reply,'bot');
      setMode(data.mode||'ai');busy=false;})
    .catch(function(){if(t)t.remove();add('Network issue \\u2014 please try again.','bot');busy=false;});}
  poll();
  send.onclick=ask;input.addEventListener('keydown',function(e){if(e.key==='Enter')ask();});
  btn.onclick=function(){panel.classList.toggle('open');if(panel.classList.contains('open'))input.focus();};
  closeBtn.onclick=function(){panel.classList.remove('open');};
  paint();
})();
""";
}
