(() => {
  let panel, host, mountedTitle, mountedGuest, mode, items = [], category = '', keyword = '', generation = 0;
  const urls = new Set(), fileUrls = new Map(), readRequests = new Set();
  const isGuest = () => !!document.querySelector('[data-guest="true"]');
  const session = () => { if (isGuest()) return null; try { return JSON.parse(localStorage.getItem('pocket-lab-live-session-v1') || 'null')?.data; } catch { return null; } };
  const isAdmin = () => (session()?.user?.accountRole || session()?.user?.role) === 'admin';
  function el(tag, cls, text) { const n=document.createElement(tag); if(cls)n.className=cls; if(text!==undefined)n.textContent=text; return n; }
  function button(text, fn, cls='sc-button') { const b=el('button',cls,text); b.type='button'; b.onclick=fn; return b; }
  function guestAction() { if(!isGuest())return false;notice('当前为游客模式，请登录后操作');if(typeof CustomEvent==='function')window.dispatchEvent(new CustomEvent('pocket-lab-guest-action'));else if(typeof uni!=='undefined')uni.showToast({title:'当前为游客模式，请登录后操作',icon:'none'});return true; }
  async function api(path='', options={}) {
    const anonymous=isGuest(),method=options.method||'GET',controller=method==='GET'?new AbortController():null;
    if(anonymous&&(method!=='GET'||options.body!==undefined||!/^(?:\?[^#]*|\/[A-Za-z0-9_-]+)?$/.test(path)||/([?&])mine=/.test(path)))throw Error('当前为游客模式，请登录后操作');
    if(controller)readRequests.add(controller);
    try{
      const res=await fetch((anonymous?'/api/public/works':'/api/showcase')+path,anonymous?{method:'GET',credentials:'omit',headers:{Accept:'application/json'},signal:controller.signal}:{...options,headers:{Authorization:'Bearer '+session()?.accessToken,...options.headers},...(controller?{signal:controller.signal}:{})});
      const result=await res.json();if(anonymous!==isGuest())throw Error('浏览模式已切换，请重新查看');if(!res.ok)throw Error(result.message||'操作失败');return result.data;
    }finally{if(controller)readRequests.delete(controller);}
  }
  const post=(path,body)=>api(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  function release() { readRequests.forEach(controller=>controller.abort());readRequests.clear();urls.forEach(url=>URL.revokeObjectURL(url)); urls.clear(); fileUrls.clear(); }
  async function fileUrl(id) {
    if(isGuest()){if(typeof id!=='string'||!/^[A-Za-z0-9_-]+$/.test(id))throw Error('文件暂时无法读取');return '/api/public/files/'+id+'/content';}
    if(fileUrls.has(id))return fileUrls.get(id);
    const task=api('/files/'+id+'/access').then(result=>result.url); fileUrls.set(id,task);return task;
  }
  const byline = work => [work.anonymous ? '匿名作者' : work.author, ...(!work.anonymous && work.className ? [work.className] : []), work.category || '其他'].filter(Boolean).join(' · ');
  function imageFor(work) { const img=el('img');img.alt=work.name;img.loading='lazy'; if(isGuest()&&(work.coverUrl||work.image)){const source=work.coverUrl||work.image;if((/^\/static\/[A-Za-z0-9_/.-]+$/.test(source)&&!source.includes('..'))||/^\/api\/public\/files\/[A-Za-z0-9_-]+\/content$/.test(source))img.src=source;}else if(work.coverId)fileUrl(work.coverId).then(url=>{if(img.isConnected)img.src=url;}).catch(()=>{img.alt='封面暂不可用';});else if(work.image)img.src=work.image;return img; }
  function notice(text) { const n=host?.querySelector('.sc-notice');if(n)n.textContent=text; }
  async function load() {
    const current=++generation, target=host; notice('正在加载作品…');
    try { let data;
      if(isGuest()){
        data=[];let page=1,total=0;
        do{const result=await api('?page='+page+'&pageSize=100');if(current!==generation||host!==target)return;if(!Array.isArray(result?.items))throw Error('公开作品暂时无法加载');data.push(...result.items);total=result.total;page++;if(!result.items.length&&data.length<total)throw Error('公开作品列表尚未完整加载');}while(data.length<total&&page<=1000);
        if(data.length<total)throw Error('公开作品较多，请稍后重试');
      }else data=await api(mode==='mine'?'?mine=true':'');
      if(current!==generation||host!==target)return;items=data;renderList(); }
    catch(error){if(host===target)notice(error.message);}
  }
  function renderList() {
    host.replaceChildren();
    const top=el('div','sc-heading'), copy=el('div');copy.append(el('h1','',mode==='mine'?(isAdmin()?'作品投稿审核':'我的投稿'):'作品展示'),el('p','',mode==='mine'?'查看投稿进度、作品内容与审核结果。':'记录灵感，展示创造。发现同学们的实验与作品。'));top.append(copy,button(mode==='mine'?'浏览公开作品':isAdmin()?'查看投稿审核':'我的投稿',()=>{if(guestAction())return;mode=mode==='mine'?'public':'mine';category='';keyword='';load();},'sc-button sc-secondary'));host.append(top);
    const tools=el('div','sc-tools'), search=el('input');search.type='search';search.placeholder='搜索作品或作者';search.setAttribute('aria-label','搜索作品或作者');search.value=keyword;
    tools.append(search,button('搜索',()=>{keyword=search.value.trim();drawCards();},'sc-button sc-secondary'));
    search.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();keyword=search.value.trim();drawCards();}};
    tools.append(button('＋ 提交作品',()=>renderForm(), 'sc-button sc-submit'));
    host.append(tools);
    const tabs=el('div','sc-tabs');const categories=['',...new Set(items.map(w=>w.category).filter(Boolean))];if(!categories.includes(category))category='';
    categories.forEach(value=>{const b=button(value||'全部作品',()=>{category=value;renderList();},'sc-chip');b.setAttribute('aria-pressed',String(category===value));tabs.append(b);});host.append(tabs,el('p','sc-notice'),el('div','sc-grid'));drawCards();
  }
  function drawCards() {
    const grid=host.querySelector('.sc-grid');grid.replaceChildren();
    const shown=items.filter(w=>(!category||w.category===category)&&(!keyword||(w.name+' '+w.author).toLowerCase().includes(keyword.toLowerCase())));
    notice(shown.length?shown.length+' 个作品':'暂无匹配作品。');
    shown.forEach(work=>{
      const card=button('',()=>renderDetail(work),'sc-card');const cover=el('div','sc-cover');cover.append(imageFor(work));
      const videos=work.mediaFiles?.filter(f=>f.type.startsWith('video/')).length||0;
      cover.append(el('span','sc-kind',videos?'▷ 视频作品':'▧ 图文作品'));
      if(mode==='mine')cover.append(el('span','sc-status',({pending:'待审核',published:'已公开',rejected:'已退回'})[work.status]));
      card.append(cover,el('h2','',work.name),el('p','sc-author',byline(work)));grid.append(card);
    });
  }
  function renderDetail(work) {
    host.replaceChildren(button('‹ 返回作品列表',renderList,'sc-back'));
    const layout=el('div','sc-detail'), content=el('div'), aside=el('aside','sc-info');
    content.append(el('h1','',work.name),el('p','sc-author',byline(work)));
    const player=el('div','sc-player'), selector=el('div','sc-media-selector');content.append(player,selector);
    const media=work.mediaFiles?.length?work.mediaFiles:work.image?[{name:'作品封面',type:'image/jpeg',static:work.image}]:[];let selected=0;
    async function show(index){if(!media.length){player.replaceChildren(el('p','','此作品暂无可预览媒体，请查看作品介绍或附件。'));return;}selected=index;player.replaceChildren(el('p','','加载中…'));selector.querySelectorAll('button').forEach((b,i)=>b.setAttribute('aria-pressed',String(i===index)));try{const f=media[index],url=f.static||await fileUrl(f.id);if(selected!==index||!player.isConnected)return;const node=el(f.type.startsWith('video/')?'video':'img');node.src=url;if(node.tagName==='VIDEO'){node.controls=true;node.preload='metadata';node.playsInline=true;}else node.alt=f.name;node.onerror=()=>{player.replaceChildren(el('p','','此媒体无法预览，请尝试 MP4（H.264）格式视频或 JPG/PNG 图片。'));};player.replaceChildren(node);}catch(e){player.replaceChildren(el('p','',e.message));}}
    media.forEach((f,i)=>selector.append(button((f.type.startsWith('video/')?'▷ ':'▧ ')+(i+1),()=>show(i),'sc-chip')));
    if(work.externalMedia && /^https?:\/\//i.test(work.externalMedia)){const link=el('a','sc-download','查看原作品媒体');link.href=work.externalMedia;link.target='_blank';link.rel='noopener noreferrer';aside.append(link);}
    aside.append(el('h2','','作品介绍'),el('p','sc-description',work.desc),el('h2','','作品附件'));
    if(!work.attachments?.length)aside.append(el('p','sc-author','作者未提供附件。'));
    work.attachments?.forEach(file=>aside.append(button('↓ '+file.name,async()=>{try{const a=el('a');a.href=await fileUrl(file.id);a.download=file.name;a.click();}catch(e){notice(e.message);}},'sc-download')));
    if(work.anonymous||work.author)aside.append(el('h2','','作者信息'));
    if(work.anonymous)aside.append(el('p','sc-author','匿名作者'));
    else if(work.author) {aside.append(el('p','sc-author','姓名：'+work.author));if(work.authorAccount)aside.append(el('p','sc-author','学号：'+work.authorAccount));if(work.className)aside.append(el('p','sc-author','班级：'+work.className));if(work.teacherName)aside.append(el('p','sc-author','指导老师：'+work.teacherName));if(work.authorContact&&work.contactPublic)aside.append(el('p','sc-author','联系方式：'+work.authorContact));}
    aside.append(el('h2','','线下展示'),el('p','sc-author',work.location||'作者未提供线下展示位置。'));
    if(work.anonymous && work.submissionIdentity && isAdmin()) aside.append(el('p','sc-audit-identity','仅管理员可见 · 投稿人：'+work.submissionIdentity.name+' · '+(work.submissionIdentity.className || '未填写班级')));
    if(work.reason)aside.append(el('p','sc-error','退回原因：'+work.reason));
    if(isAdmin()&&work.status==='pending'){
      const reason=el('textarea');reason.placeholder='退回时请填写原因';reason.setAttribute('aria-label','退回原因');aside.append(reason);
      const actions=el('div','sc-review');const review=async status=>{actions.querySelectorAll('button').forEach(b=>b.disabled=true);try{await post('/'+work.id+'/review',{status,reason:reason.value});await load();}catch(e){notice(e.message);actions.querySelectorAll('button').forEach(b=>b.disabled=false);}};
      actions.append(button('通过并公开',()=>review('published')),button('退回修改',()=>review('rejected'),'sc-button sc-secondary'));aside.append(actions);
    }
    layout.append(content,aside);host.append(layout,el('p','sc-notice'));show(0);
  }
  function renderForm() {
    if(guestAction())return;
    host.replaceChildren(button('‹ 返回作品列表',renderList,'sc-back'));
    const heading=el('div','sc-heading');const headingCopy=el('div');headingCopy.append(el('h1','','发布你的作品'),el('p','','先展示作品，再分享制作过程。提交后由管理员审核公开。'));heading.append(headingCopy);host.append(heading);
    const form=el('form','sc-form'), body=el('div','sc-form-main'), side=el('aside','sc-form-side');let uploading=false;
    const groups={cover:[],media:[],attachment:[]};
    function uploadGroup(kind,title,description,accept,multiple){
      const section=el('section','sc-upload-section');section.append(el('h2','',title),el('p','sc-author',description));
      const label=el('label','sc-drop'),input=el('input');input.type='file';input.accept=accept;input.multiple=multiple;input.setAttribute('aria-label',title);label.append(el('span','','＋ 选择文件或拖入这里'),input);const previews=el('div','sc-previews');section.append(label,previews);
      function paint(){previews.replaceChildren();groups[kind].forEach(entry=>{const card=el('div','sc-preview');if(kind!=='attachment'){const media=el(entry.file.type.startsWith('video/')?'video':'img');media.src=entry.url;if(media.tagName==='VIDEO'){media.controls=true;media.preload='metadata';}else media.alt=entry.file.name;card.append(media);}card.append(el('span','',entry.file.name),button('移除',()=>{if(uploading)return;groups[kind]=groups[kind].filter(x=>x!==entry);URL.revokeObjectURL(entry.url);urls.delete(entry.url);paint();},'sc-back'));previews.append(card);});}
      function add(files){if(uploading)return;error.textContent='';const limit=kind==='cover'?1:kind==='media'?12:5;if((kind==='cover'?0:groups[kind].length)+files.length>limit){error.textContent='此区域最多选择 '+limit+' 个文件。';return;}
        const pattern=kind==='attachment'?/\.(zip|pdf|docx?|pptx?|txt)$/i:kind==='cover'?/\.(jpe?g|png|webp)$/i:/\.(jpe?g|png|webp|mp4|webm|mov)$/i;
        if(files.some(f=>!pattern.test(f.name)||f.size===0||f.size>=(kind==='attachment'||/\.(mp4|webm|mov)$/i.test(f.name)?5*1024**3:30*1024**2))){error.textContent='文件格式或大小不符合要求，请检查后重新选择。';return;}
        if(kind==='cover'){groups.cover.forEach(e=>{URL.revokeObjectURL(e.url);urls.delete(e.url);});groups.cover=[];}
        files.forEach(file=>{const url=URL.createObjectURL(file);urls.add(url);groups[kind].push({file,url});});paint();input.value='';}
      input.onchange=()=>add([...input.files]);label.ondragover=e=>{e.preventDefault();label.classList.add('dragging');};label.ondragleave=()=>label.classList.remove('dragging');label.ondrop=e=>{e.preventDefault();label.classList.remove('dragging');add([...e.dataTransfer.files]);};return section;
    }
    const error=el('p','sc-error');error.setAttribute('role','alert');
    body.append(uploadGroup('media','照片 / 视频','最多 12 个；图片 <30MB，视频 <5GB。建议使用 MP4（H.264）。','.jpg,.jpeg,.png,.webp,.mp4,.webm,.mov',true));
    function field(label,tag='input'){const wrap=el('label','sc-field',label),input=el(tag);input.setAttribute('aria-label',label);wrap.append(input);body.append(wrap);return input;}
    const name=field('作品标题');name.required=true;name.maxLength=20;name.placeholder='作品标题不超过20字';
    const desc=field('作品介绍','textarea');desc.required=true;desc.maxLength=200;desc.rows=5;desc.placeholder='不超过200字，介绍主要功能、实现思路和制作过程…';
    body.append(el('h2','','作者信息'),el('p','sc-author','学号：'+(session()?.user?.account||'')+' · 姓名：'+(session()?.user?.name||'')));
    const classField=field('班级');classField.value=session()?.user?.className||'';classField.maxLength=100;
    const teacherInput=field('指导老师（选填）');
    teacherInput.maxLength=100;teacherInput.placeholder='输入姓名或工号';
    const teacherStatus=el('p','sc-author'),teacherSuggestions=el('div','sc-teacher-suggestions');
    teacherSuggestions.setAttribute('role','listbox');teacherSuggestions.setAttribute('aria-label','指导老师候选');
    teacherInput.after(teacherStatus,teacherSuggestions);
    let teacherRevision=0,teacherTimer,teacherId=null,matchedTeacherText='';
    const teacherContext=()=>({token:session()?.accessToken,revision:++teacherRevision});
    const teacherCurrent=ctx=>ctx.revision===teacherRevision&&ctx.token===session()?.accessToken&&form.isConnected;
    const useTeacher=teacher=>{
      teacherRevision++;clearTimeout(teacherTimer);
      teacherInput.value=teacher.name;matchedTeacherText=teacher.name;teacherId=teacher.id;
      teacherStatus.textContent='已匹配教师账号';teacherSuggestions.replaceChildren();
    };
    const fetchTeachers=async(value,exact)=>{
      const ctx=teacherContext();
      try{
        const response=await fetch('/api/users/teachers?pageSize=20&keyword='+encodeURIComponent(value)+(exact?'&exact=true':''),
          {headers:{Authorization:'Bearer '+ctx.token}});
        const payload=await response.json();
        if(!teacherCurrent(ctx))return null;
        if(!response.ok)throw Error(payload.message||'教师名单暂时无法读取');
        return payload.data;
      }catch(e){if(!teacherCurrent(ctx))return null;throw e;}
    };
    const searchTeachers=async()=>{
      const value=teacherInput.value.trim();if(!value)return;
      teacherStatus.textContent='正在查询…';
      try{
        const data=await fetchTeachers(value,false);if(!data)return;
        teacherSuggestions.replaceChildren();
        for(const teacher of data.items){
          const option=button([teacher.name,teacher.department,teacher.account].filter(Boolean).join(' · '),
            ()=>useTeacher(teacher),'sc-teacher-option');
          option.setAttribute('role','option');
          option.onmousedown=event=>event.preventDefault();
          teacherSuggestions.append(option);
        }
        teacherStatus.textContent=data.items.length?'输入完整姓名或工号，或点击匹配结果。':'未找到指导老师，请核对姓名或工号。';
      }catch(e){if(form.isConnected)teacherStatus.textContent=e.message;}
    };
    const resolveTeacher=async()=>{
      clearTimeout(teacherTimer);
      const value=teacherInput.value.trim();
      if(!value){teacherRevision++;teacherId=null;matchedTeacherText='';teacherStatus.textContent='';teacherSuggestions.replaceChildren();return null;}
      if(teacherId&&matchedTeacherText===value)return teacherId;
      const data=await fetchTeachers(value,true);
      if(!data)throw Error('教师信息已变化，请重新核对后提交');
      if(data.total!==1){
        teacherStatus.textContent=data.total?'存在同名教师，请填写工号或点选对应教师。':'未找到指导老师，请核对姓名或工号。';
        throw Error(teacherStatus.textContent);
      }
      useTeacher(data.items[0]);return teacherId;
    };
    teacherInput.oninput=()=>{
      teacherRevision++;clearTimeout(teacherTimer);teacherId=null;matchedTeacherText='';
      teacherStatus.textContent='';teacherSuggestions.replaceChildren();
      if(teacherInput.value.trim())teacherTimer=setTimeout(searchTeachers,220);
    };
    teacherInput.onblur=()=>{
      const value=teacherInput.value.trim();
      resolveTeacher().catch(e=>{if(form.isConnected&&teacherInput.value.trim()===value&&!teacherId)teacherStatus.textContent=e.message;});
    };

    const contactField=field('联系方式（选填）');contactField.maxLength=200;
    const locationField=field('线下展示位置（选填）');locationField.maxLength=200;locationField.placeholder='例如：潇湘校区智信楼101教室7号展位';
    const equipmentField=field('关联器材（选填）','select');equipmentField.append(el('option','','不关联器材'));equipmentField.firstChild.value='';
    fetch('/api/equipment?pageSize=100',{headers:{Authorization:'Bearer '+session()?.accessToken}}).then(r=>r.json()).then(result=>{for(const item of result.data?.items||[]){const option=el('option','',item.name);option.value=item.id;equipmentField.append(option);}}).catch(()=>{});
    const categoryField=field('作品分类','select');['单片机','FPGA','物联网','嵌入式','机器人','人工智能','其他'].forEach(text=>{const option=el('option','',text);option.value=text;categoryField.append(option);});
    side.append(uploadGroup('cover','作品封面','必选 1 张图片，建议 16:9；图片 <30MB。','.jpg,.jpeg,.png,.webp',false),uploadGroup('attachment','附件（选填）','ZIP 源码、PDF 文档等；最多 5 个，每个 <5GB。','.zip,.pdf,.doc,.docx,.ppt,.pptx,.txt',true));
    const privacy=el('fieldset','sc-privacy'),privacyLegend=el('legend','','发布方式 *');
    privacy.append(privacyLegend);
    const anonymous=el('input'),contactPublic=el('input'),privacyHint=el('p','sc-author');
    const privacyOptions=el('div','sc-privacy-options');
    for(const [input,label,value] of [[anonymous,'匿名发布','anonymous'],[contactPublic,'公开联系方式','public']]){
      input.type='radio';input.name='showcase-privacy';input.value=value;input.setAttribute('aria-label',label);
      const wrap=el('label','sc-privacy-label');wrap.append(input,el('span','',label));privacyOptions.append(wrap);
    }
    const updatePrivacy=()=>{
      privacyHint.textContent=anonymous.checked?'公开显示为“匿名作者”，隐藏作者身份及联系方式。':
        contactPublic.checked?'实名展示作者资料和已填写的联系方式。':'请选择一种发布方式后提交。';
    };
    anonymous.onchange=contactPublic.onchange=updatePrivacy;
    updatePrivacy();privacy.append(privacyOptions,privacyHint);body.append(privacy);
    const footer=el('div','sc-form-footer'),submit=el('button','sc-button','提交审核');submit.type='submit';footer.append(el('p','sc-author','审核通过后，封面、媒体和附件将对登录用户公开。'),error,submit);
    form.append(body,side,footer);host.append(form);
    form.onsubmit=async event=>{event.preventDefault();if(uploading)return;if(!anonymous.checked&&!contactPublic.checked){error.textContent='请选择发布方式';return;}if(!groups.media.length||!groups.cover.length){error.textContent='请添加作品封面和至少一个照片或视频。';return;}uploading=true;submit.disabled=true;form.querySelectorAll('input,textarea,select').forEach(n=>n.disabled=true);try{
      const matchedTeacher=await resolveTeacher();teacherSuggestions.replaceChildren();
      const all=Object.entries(groups).flatMap(([kind,list])=>list.map(entry=>({kind,entry})));let i=0;
      for(const {kind,entry} of all){submit.textContent='上传文件 '+(++i)+' / '+all.length;if(!entry.saved)entry.saved=await api('/files?kind='+kind+'&name='+encodeURIComponent(entry.file.name),{method:'POST',body:entry.file});}
      submit.textContent='正在提交…';await post('',{anonymous:anonymous.checked,authorClassName:classField.value,teacherUserId:matchedTeacher,authorContact:contactField.value,contactPublic:!anonymous.checked&&contactPublic.checked,location:locationField.value,name:name.value,desc:desc.value,category:categoryField.value,equipmentId:equipmentField.value,coverId:groups.cover[0].saved.id,mediaIds:groups.media.map(x=>x.saved.id),attachmentIds:groups.attachment.map(x=>x.saved.id)});
      mode='mine';keyword='';category='';await load();notice('提交成功，等待管理员审核。');
    }catch(e){error.textContent=e.message;}finally{uploading=false;submit.disabled=false;submit.textContent='提交审核';form.querySelectorAll('input,textarea,select').forEach(n=>n.disabled=false);updatePrivacy();}};
  }
  function mount(){
    const main=document.querySelector('.main');const next=main?.querySelector(':scope > .module-panel');const title=next?.querySelector('.page-title')?.textContent.trim();
    const supported=['作品展示','作品投稿','新增作品投稿'].includes(title);
    if(next===panel&&title===mountedTitle&&mountedGuest===isGuest()&&host?.isConnected)return;
    if(!supported){if(host){host.remove();panel?.classList.remove('sc-replaced');host=null;panel=null;generation++;release();}return;}
    host?.remove();panel?.classList.remove('sc-replaced');release();panel=next;mountedTitle=title;mountedGuest=isGuest();panel.classList.add('sc-replaced');host=el('section','showcase-app');panel.after(host);mode=!isGuest()&&title.includes('投稿')?'mine':'public';category='';keyword='';
    if(title==='新增作品投稿'&&!isGuest())renderForm();else load();
  }
  let scheduled=false;new MutationObserver(()=>{if(scheduled)return;scheduled=true;requestAnimationFrame(()=>{scheduled=false;mount();});}).observe(document.getElementById('app'),{childList:true,subtree:true,attributes:true,attributeFilter:['data-guest']});mount();
})();
