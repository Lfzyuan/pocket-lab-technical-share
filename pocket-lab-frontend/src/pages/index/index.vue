<template>
<view :data-guest="s.guest?'true':undefined" :class="['portal',{'portal-auth':!s.user}]" :aria-busy="s.busy||s.loading">
  <view v-if="s.starting" class="starting">正在确认登录状态…</view>
  <view v-else-if="!s.user" class="auth-page">
    <view class="auth-layout">
      <view class="auth-intro"><BrandIdentity/><view class="auth-intro-copy"><text class="auth-slogan">让每一个想法，<br/>都有动手的机会。</text><text class="auth-description">连接器材、空间与学习资源，<br/>从这里开始你的实验。</text></view><text class="auth-intro-footer">口袋实验室线上服务平台</text></view>
      <view class="auth-form-area"><view class="auth-card">
      <BrandIdentity class="auth-mobile-brand"/>
      <text class="eyebrow">学院实验室服务平台</text><text class="auth-title">账号登录</text>
      <text class="muted">使用管理员创建的账号登录，借用进度可跨设备查看。</text>
      <view class="field-group"><text>学号 / 工号</text><input v-model="s.login.account" class="field" placeholder="请输入学号或工号" maxlength="32" @confirm="login" /></view>
      <view class="field-group"><text>密码</text><view class="password-field"><input v-model="s.login.password" class="field" :password="!s.showPassword" placeholder="请输入密码" maxlength="128" @confirm="login"/><button role="button" class="password-toggle" :aria-label="s.showPassword?'隐藏密码':'显示密码'" :aria-pressed="s.showPassword" @click="s.showPassword=!s.showPassword"><image :src="s.showPassword?'/static/icons/eye-open.png':'/static/icons/eye-closed.png'" class="password-toggle-icon" mode="aspectFit" aria-hidden="true"/></button></view></view>
      <view v-if="s.error" class="error" role="alert">{{s.error}}</view>
      <button role="button" class="btn full" :disabled="s.busy" :aria-disabled="s.busy" @click="login">{{s.busy?'正在登录…':'登录'}}</button>
      <text class="auth-help">本平台暂不开放自助注册；没有账号或者忘记密码的，请联系实验室管理员。</text>
      <view class="auth-guest-entry"><text>先了解口袋实验室</text><button role="button" class="btn secondary full" :disabled="s.busy" :aria-disabled="s.busy" @click="enterGuest">游客模式</button><text>无需登录，浏览器材、实验室与公开作品。</text></view>
      </view></view>
    </view>
  </view>
  <template v-else>
    <view class="sidebar">
      <BrandIdentity compact @click="go('home')"/>
      <button role="button" v-for="n in primaryNav" :key="n.id" :class="['nav-item',{active:activePrimary(s.page,s.user.role)===n.id}]" :aria-current="activePrimary(s.page,s.user.role)===n.id?'page':undefined" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button>

    </view>
    <view class="workspace">
      <view class="topbar"><view><text class="breadcrumb">口袋实验室 / {{heading}}</text><text class="mobile-heading">{{heading}}</text></view><view class="top-actions"><text class="role-pill">{{s.guest?'游客模式':s.user.accountType==='teacher'?'教师':roles[s.user.role]}}</text><button role="button" class="text-btn top-name" @click="go('personal')">{{s.user.name}}</button><button role="button" :class="['text-btn', {'logout-mobile-hidden':s.page!=='personal'}]" :disabled="s.busy" :aria-disabled="s.busy" @click="logout">{{s.guest?'返回登录':'退出登录'}}</button></view></view>
      <view class="main">
        <view v-if="s.notice" class="success" role="status">{{s.notice}}</view>
        <view v-if="s.error&&(!s.dialog||s.dialog==='equipment')" class="error" role="alert">{{s.error}}</view>
        <view v-if="!s.verified" class="warning">当前连接尚未确认，已暂停提交操作。已有内容仅供查看，请刷新连接后再办理。</view>
        <view v-if="s.pending" class="warning pending-operation"><text class="card-title">{{regulationPending?'有一笔正文发布结果待确认':'有一笔提交结果待确认'}}</text><text>{{regulationPending?'正文可能已保存。请先核对已发布内容；重试将沿用原操作凭证，不会另建一次发布。':'提交可能已在服务器完成。重试会沿用原操作凭证，请勿重新创建同一申请。'}}</text><view class="button-row"><button role="button" class="btn small" :disabled="s.busy||!s.verified" :aria-disabled="s.busy||!s.verified" @click="retryPending">重试确认上次操作</button><button role="button" class="btn secondary small" @click="regulationPending?openRules(s.pending.path.split('/')[2]):go(s.user.role==='student'?'records':'handover')">{{regulationPending?'核对已发布正文':'查询借用记录'}}</button></view><checkbox-group @change="s.pendingChecked=$event.detail.value.includes('confirmed')"><label class="check-row"><checkbox value="confirmed" :checked="s.pendingChecked"/><text>{{regulationPending?'已核对已发布正文，确认上次发布结果':'已通过记录和事件核对上次结果'}}</text></label></checkbox-group><button role="button" class="text-btn" @click="acknowledgePending">核对后清除本机提示</button></view>
    <template v-if="s.page==='home'&&homeRuleKind">
      <button role="button" class="text-btn detail-back" @click="closeHomeRules">‹ 返回首页</button>
      <RegulationDocument :kind="homeRuleKind" workspace class="panel" />
    </template>
    <OtherBorrowPage v-else-if="s.dialog==='other-record'" :key="'other:'+s.selected.id" :record-id="s.selected.id" :return-label="heading" :user="s.user" :verified="s.verified" :pending="!!s.pending" @pending="s.pending=$event" @close="close" @changed="refreshUnifiedLoans"/>
    <template v-else-if="s.dialog==='record'">
      <button role="button" class="text-btn detail-back" @click="close">‹ 返回{{heading}}</button><view class="panel record-document"><view v-if="s.error" class="error" role="alert">{{s.error}}</view>
      <view class="card-title-row"><text class="detail-title">{{recordEquipment[s.selected.equipmentId]||s.selected.equipmentId}}</text><text :class="['status',s.selected.status]">{{labels[s.selected.status]}}</text></view><view class="detail-lines"><text>借用人：{{s.selected.user}} · {{s.selected.className}}</text><text v-if="s.selected.userAccount||s.selected.account">学号 / 工号：{{s.selected.userAccount||s.selected.account}}</text><text v-if="s.selected.phone&&!s.pickupProxyRecords&&(!s.selected.pickupProxyId||s.user.role!=='student')">{{s.selected.pickupProxyId?'领取联系电话':'联系电话'}}：{{s.selected.phone}}</text><text v-if="s.selected.purpose">用途：{{s.selected.purpose}}</text><text v-if="s.selected.pickupProxyName">代领人：{{s.selected.pickupProxyName}} · {{s.selected.pickupProxyAccount}}</text><text v-if="s.selected.pickupMode==='agreed'">约定领取日期：{{s.selected.agreedPickupDate||s.selected.agreedPickupAt?formatBorrowDate(s.selected.agreedPickupDate||s.selected.agreedPickupAt):'另行约定'}}</text><text v-if="s.selected.pickupSlot">代领时间：{{pickupSlotLabel(s.selected.pickupSlot)}}</text><text v-else-if="s.selected.pickupProxyId&&s.selected.status==='approved'">代领时间：待代领人选择</text><text>申请日期：{{formatBorrowDate(s.selected.createdAt)}}</text><text v-if="s.selected.status==='approved'">领取截止：{{s.selected.pickupDeadlineAt?formatBorrowDate(s.selected.pickupDeadlineAt):'尚未设置'}}</text><text v-if="s.selected.issuedAt">领取日期：{{formatBorrowDate(s.selected.issuedAt)}}</text><text v-if="s.selected.returnedAt">归还日期：{{formatBorrowDate(s.selected.returnedAt)}}</text><text v-if="s.selected.status!=='returning'&&s.selected.dueDate" class="due-date">预计归还：{{s.selected.dueDate}} · 已续借 {{s.selected.renewCount}} 次</text><text v-if="s.selected.returnRequestedAt">申请归还日期：{{formatBorrowDate(s.selected.returnRequestedAt)}}</text><text v-if="s.selected.status==='returning'&&s.selected.returnDeadlineAt" class="due-date">交回截止：{{formatBorrowMinute(s.selected.returnDeadlineAt)}}</text><text v-if="s.selected.assetNumber">资产编号：{{s.selected.assetNumber}}</text></view><view class="record-hint">{{recordHint}}</view>
      <view v-if="can('issue')||can('return-confirm')" class="verification">
        <text class="block-title">现场核验</text>
        <view v-if="can('issue')||s.selected.assetNumber" class="field-group">
          <text>{{can('issue')?'实际发放资产编号（无编号可跳过）':'归还核对资产编号'}}</text>
          <input v-model="s.form.assetNumber" class="field" placeholder="扫描或手动填写实际编号" maxlength="100"/>
          <AssetBarcodeScanner :key="s.selected.id+'-'+s.selected.status" :disabled="s.busy||!s.verified||!!s.pending" :confirm-label="can('issue')?'确认发放':'确认归还'" :context="recordEquipment[s.selected.equipmentId]+' · '+s.selected.user" @scanned="detailScanned"/>
        </view>
        <text class="muted">{{can('issue')&&s.selected.pickupProxyId?'发放前请核对代领人身份、器材及配件。':'确认操作前，请现场核对身份、器材及配件。'}}</text>
      </view>
      <view v-if="can('reject')||can('return-reject')" class="field-group"><text>拒绝 / 退回原因（拒绝时必填）</text><textarea v-model="s.form.reason" class="field textarea" placeholder="请填写具体原因" maxlength="500"/></view>
      <view v-if="can('schedule-pickup')" class="field-group">
        <text>选择代领值班时间</text>
        <DutyPicker :model-value="s.form.slotId" :slots="s.form.slots" :saved-slot="s.selected.pickupSlot" :loading="s.form.slotsLoading" :loaded="s.form.slotsLoaded" :error="s.form.slotsError" :warning="s.form.slotWarning" :disabled="s.busy||!s.verified||!!s.pending" @update:model-value="selectPickupSlot" @refresh="loadProxySlots"/>
      </view>
      <view v-if="can('extend-pickup')" class="verification"><text class="block-title">延长领取时间</text><text class="muted">已与借用人沟通特殊情况时，填写新的截止时间及延期说明；历史未设置截止的记录也可在此补设。</text><view class="time-fields"><DateTimeField v-model="s.form.deadlineDate" mode="date"/><DateTimeField v-model="s.form.deadlineTime" mode="time"/></view><view class="field-group"><text>延期说明</text><textarea v-model="s.form.reason" class="field textarea" maxlength="500"/></view></view>
      <view class="button-row record-actions"><button v-if="s.user.id===s.selected.userId&&s.user.role==='student'&&s.selected.status==='returning'" role="button" class="btn secondary" @click="go('duty')">查看归还值班时间</button><button role="button" v-for="action in recordActions" :key="action" :class="['btn', ['reject','cancel','return-reject'].includes(action)?'secondary':'']" :disabled="s.busy||!s.verified||!!s.pending||(action==='schedule-pickup'&&(s.form.slotsLoading||!s.form.slotsLoaded||!!s.form.slotsError||!s.form.slotId))" :aria-disabled="s.busy||!s.verified||!!s.pending||(action==='schedule-pickup'&&(s.form.slotsLoading||!s.form.slotsLoaded||!!s.form.slotsError||!s.form.slotId))" @click="act(action)">{{action==='cancel'&&s.selected.status==='pending'?'撤回申请':action==='renew'?renewLabel(s.selected):actionNames[action]}}</button></view>
      <text v-if="!recordActions.length" class="muted">当前没有可办理的操作。</text><text class="block-title history-heading">办理记录</text><view v-for="event in s.selected.events" :key="event.id" class="event"><text>{{event.action==='cancel'?(event.actorType==='system'?'系统自动取消':event.fromStatus==='pending'?'撤回申请':'取消领取'):actionNames[event.action]||'提交申请'}} · {{labels[event.toStatus]}}</text><text class="small">{{formatBorrowMinute(event.at)}}</text><text v-if="event.reason" class="reason">{{event.reason}}</text></view>
      </view>
    </template>

    <template v-else-if="['equipment','apply'].includes(s.dialog)">
      <button role="button" class="text-btn detail-back" @click="close">‹ 返回器材目录</button><view class="equipment-detail-layout"><view class="panel equipment-detail-main">
      <image :src="s.selected.image||'/static/board-blue.png'" class="detail-image" mode="aspectFit"/><text class="detail-title">{{s.selected.name}}</text><text class="muted">{{s.selected.brand}} · {{s.selected.model}}</text><view class="stock-box"><text class="stock">{{s.selected.available}} 件可借</text><text>{{s.selected.requiresApproval?'申请借用 · 审批后领取':'预约领取 · 免审批'}}</text></view><view class="detail-lines"><text><text class="muted">规格：</text>{{s.selected.spec}}</text><text><text class="muted">用途：</text>{{s.selected.desc}}</text><text><text class="muted">位置：</text>{{s.selected.location}}</text><text><text class="muted">配件：</text>{{s.selected.accessories}}</text><text v-if="s.selected.requiresAsset">领取和归还须核对真实资产编号。</text></view><button v-if="s.user.role==='student'&&s.activeEquipmentBorrow" role="button" class="btn full" @click="go('records')">查看我的借用 →</button><button role="button" v-if="s.user.role==='student'&&!s.activeEquipmentBorrow" class="btn full" :disabled="!s.guest&&(s.busy||!s.verified||!s.selected.active||(!s.selected.requiresApproval&&s.selected.available<1))" :aria-disabled="!s.guest&&(s.busy||!s.verified||!s.selected.active||(!s.selected.requiresApproval&&s.selected.available<1))" @click="beginApply">{{s.selected.requiresApproval?'申请借用':'预约领取'}}</button><text class="small">同一人同一种器材的进行中记录最多1件，默认同时未归还器材最多3件；经管理员确认的例外额度以系统为准。</text>
      <view class="equipment-interest"><text>收藏 {{s.guest?'—':s.selected.favoriteCount??0}} 人</text></view><view class="button-row"><button v-if="s.user.role==='student'" role="button" class="btn secondary" :disabled="!s.guest&&(s.busy||!s.verified)" @click="toggleFavorite">{{favorited?'已收藏 · 取消收藏':'收藏器材'}}</button><button role="button" class="btn secondary" @click="relatedResources(s.selected.id)">查看相关资料 →</button></view>
      </view><view class="detail-related"><view class="panel"><text class="block-title">相关学习资料</text><text v-if="relatedLoading" class="muted">正在查找相关资料…</text><text v-if="relatedError" class="error">{{relatedError}}</text><button v-for="row in related.resources" :key="row.id" role="button" class="related-link" @click="relatedResources(s.selected.id)">{{row.name}} ›</button><text v-if="!relatedLoading&&!relatedError&&!related.resources.length" class="muted">暂无关联资料</text><button role="button" class="text-btn" @click="relatedResources(s.selected.id)">进入资料分类 ›</button></view><view class="panel"><text class="block-title">相关作品</text><button v-for="row in related.works" :key="row.id" role="button" class="related-link" @click="relatedWorks(s.selected.id)">{{row.name}} ›</button><text v-if="!relatedLoading&&!relatedError&&!related.works.length" class="muted">暂无关联作品</text></view></view></view>
    </template>

        <template v-else-if="s.guest&&isGuestPrivatePage(s.page)"><view class="section-top"><view><text class="page-title">{{heading}}</text><text class="muted">当前为游客模式，请登录后查看个人信息或办理业务。</text></view></view><view class="panel guest-account-notice"><text class="block-title">登录后使用{{heading}}</text><text class="muted">游客可以浏览公开器材、实验室、作品、学习资源和心愿清单。</text><button role="button" class="btn" @click="showGuestNotice">{{guestPageAction}}</button><button role="button" class="text-btn" @click="leaveGuest">去登录 ›</button></view></template>
        <template v-else-if="s.page==='home'">
          <PublicAnnouncements/>
          <button v-if="!s.guest" role="button" class="btn secondary small home-inbox" @click="go('notifications')" :aria-label="unreadMessages?'我的消息，'+unreadMessages+' 条未读':'我的消息'">
            我的消息 <text v-if="unreadMessages" class="home-inbox-dot"></text>
          </button>
          <view class="panel rules-summary"><text class="block-title">管理办法与使用须知</text><view class="rules-links"><button role="button" class="text-btn" @click="openHomeRules('management')">1. 口袋实验室管理办法（试行） <text>阅读 ›</text></button><button role="button" class="text-btn" @click="openHomeRules('usage')">2. 口袋实验室线上服务平台使用须知 <text>阅读 ›</text></button></view></view>
          <view class="section-top"><view><text class="page-title">{{s.user.role==='student'?'你好，'+s.user.name:s.user.role==='reviewer'?'老师工作台':'管理工作台'}}</text><text class="muted">{{s.user.role==='student'?'查看器材、申请借用，跟进领取与归还进度。':'查看共享库存和借用进度，办理学院器材事务。'}}</text></view></view>
          <view class="stats-grid home-stats"><button role="button" v-for="status in ['pending','approved','borrowed','returning']" :key="status" class="stat" @click="openStatus(status)"><text class="stat-number">{{s.counts[status]??'—'}}</text><text class="muted">{{labels[status]}}</text></button></view>
          <view v-if="s.user.role==='student'" class="home-hero"><view><text class="eyebrow">Pocket Lab · 学习与实践</text><text class="hero-title">让每一个想法，<br/>都有动手的机会。</text><text class="hero-copy">借用实验器材，探索真实问题，分享你的作品。</text><button role="button" class="btn" @click="go('equipment')">浏览器材 →</button></view><image src="/static/board-blue.png" mode="aspectFit" class="hero-board"/></view>
          <view v-if="s.user.role!=='student'" class="panel"><text class="block-title">工作办理</text><view class="service-grid management-grid"><button v-for="n in staffShortcuts" :key="n.id" role="button" class="service-item" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button></view></view>
          <LabBookingSummary v-if="s.user.role==='admin'" ref="labSummary" :user="s.user" @navigate="openLabStatus"/>
          <WeeklyDutyPanel @all="go('duty')"/>


        </template>
        <template v-else-if="s.page==='personal'"><view class="section-top"><view><text class="page-title">个人中心</text><text class="muted">{{s.user.name}} · {{s.user.accountType==='teacher'?'教师':roles[s.user.role]}}</text></view></view><view class="panel personal-identity"><view class="avatar">{{s.user.name.slice(0,1)}}</view><view><text class="card-title">{{s.user.name}}</text><text class="muted">{{s.guest?'登录后可使用个人服务':s.user.account+' · '+(s.profile.className||'欢迎来到口袋实验室')}}</text></view><button role="button" class="text-btn" @click="go('profile')">修改资料 ›</button></view><view v-if="s.user.role==='student'" class="panel"><view class="section-heading"><text class="block-title">我的借用</text><button role="button" class="text-btn" @click="go('records')">全部记录 ›</button></view><view class="personal-statuses"><button v-for="st in ['pending','approved','borrowed','returning']" :key="st" role="button" class="service-item" @click="openStatus(st)"><text class="stat-number">{{s.counts[st]??'—'}}</text><text>{{labels[st]}}</text></button></view></view><view class="panel"><text class="block-title">{{s.user.role==='student'?'我的服务':'工作服务'}}</text><view class="service-grid"><button v-for="n in personalServices" :key="n.id" role="button" class="service-item" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button></view></view><view class="panel"><text class="block-title">账号与帮助</text><view class="service-grid"><button v-for="n in helpServices" :key="n.id" role="button" class="service-item" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button></view></view></template>
        <RulesDocuments v-else-if="s.page==='rules'" :user="s.user" :verified="s.verified" :initial-kind="ruleKind" :pending="s.pending" @read="openRules" @pending="s.pending=$event"/>
        <template v-else-if="s.page==='management'||s.page==='approvals'"><view class="section-top"><view><text class="page-title">{{s.page==='approvals'?'审批中心':'管理中心'}}</text><text class="muted">{{s.page==='approvals'?'按业务类型处理待办申请。':'选择需要维护的事项。'}}</text></view></view><view class="panel"><view class="service-grid management-grid"><button v-for="n in s.page==='approvals'?approvalNav:managementNav" :key="n.id" role="button" class="service-item" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button></view></view></template>
        <WishlistPage v-else-if="s.page==='wishlist'" :key="s.user.id" :user="s.user" :verified="s.verified" @pending="s.pending=$event" @inventory="go('inventory')" @borrow="openStockedWish"/>
        <OtherBorrowPage v-else-if="s.page==='otherBorrows'" :key="s.user.id+':'+s.user.role" ref="otherBorrowPage" :user="s.user" :verified="s.verified" :pending="!!s.pending" @pending="s.pending=$event" @navigate="go($event)" @created="otherBorrowCreated"/>
        <template v-else-if="s.page==='equipment'">
          <view class="section-top">
            <view><text class="page-title">{{s.user.role==='student'?'器材目录':'器材库存'}}</text><text class="muted">可借数量已扣除待领取预留及借出数量，以提交时核验为准。</text></view>
            <button v-if="s.user.role==='reviewer'&&canSwitchBorrowerView" role="button" class="btn secondary" :disabled="s.busy||!s.verified||!!s.pending" @click="switchBorrowerView('user','')">切换用户视角借用</button>
            <button v-else-if="canSwitchBorrowerView" role="button" class="btn secondary" :disabled="s.busy||!s.verified||!!s.pending" @click="switchBorrowerView('management','')">返回管理视角</button>
          </view>
          <view class="filters"><view class="search-box"><input v-model="s.catalogFilter.keyword" class="field" placeholder="搜索器材名称或型号" maxlength="100" @confirm="searchEquipment"/><button role="button" class="btn" :disabled="s.loading" :aria-disabled="s.loading" @click="searchEquipment">搜索</button></view><view class="chips"><button role="button" v-for="category in categories" :key="category" :class="['chip',{selected:s.catalogFilter.category===category}]" @click="chooseCategory(category)">{{category||'全部'}}</button><button role="button" class="chip" @click="go('otherBorrows')">其他借用</button></view></view>
          <view v-if="s.catalog.stale" class="warning">当前展示本机缓存，库存可能已变化。连接恢复后请刷新再申请。</view>
          <text v-if="s.catalog.at" class="updated">最近校验：{{formatTime(new Date(s.catalog.at).toISOString())}}</text>
          <view v-if="s.loading" class="loading" role="status">正在更新器材…</view>
          <view class="button-row view-switch"><button role="button" :class="['chip',{selected:s.catalogView==='cards'}]" @click="setCatalogView('cards')">▦ 卡片</button><button role="button" :class="['chip',{selected:s.catalogView==='list'}]" @click="setCatalogView('list')">☷ 列表</button><text class="small">{{s.catalogView==='cards'?'每页9项':'名称与型号快速浏览'}}</text></view><view :class="['equipment-grid',{'equipment-list':s.catalogView==='list'}]"><button role="button" v-for="e in s.catalog.items" :key="e.id" class="equipment-card" :data-equipment-id="e.id" @click="openEquipment(e)"><view v-if="s.catalogView==='list'" class="equipment-list-entry"><image v-if="e.image" :src="e.image" class="equipment-list-thumbnail" mode="aspectFit" aria-label="器材图片"/><view class="equipment-list-body"><view class="equipment-list-heading"><view class="equipment-list-identity"><text class="equipment-list-name">{{e.name}}</text><text v-if="e.model" class="equipment-list-model">{{e.model}}</text><text v-if="!e.active" class="status cancelled">已下架</text></view><text :class="['equipment-list-available',{emptyStock:e.available===0}]">{{e.available}} 件可借</text></view><view class="equipment-list-meta"><text v-if="e.location" class="equipment-list-location">{{e.location}}</text><text>收藏 {{s.guest?'—':e.favoriteCount??0}} 人</text><text v-if="s.user.role!=='student'">总数 {{e.stock}} · 预留 {{e.reserved}} · 借出 {{e.borrowed}}</text><text class="equipment-list-action borrow-kind-label">{{e.requiresApproval?'申请借用':'预约领取'}} ›</text></view></view></view><template v-else><view class="card-image"><image :src="e.image||'/static/board-blue.png'" mode="aspectFit"/></view><view class="card-body"><view class="card-title-row"><text class="card-title">{{e.name}}</text><text v-if="!e.active" class="status cancelled">已下架</text></view><text class="muted">{{e.model}}</text><text class="location">{{e.location}}</text><view class="card-footer"><text :class="['stock',{emptyStock:e.available===0}]">{{e.available}} 件可借</text><text class="small borrow-kind-label">{{e.requiresApproval?'申请借用':'预约领取'}}</text></view><view class="equipment-interest"><text>收藏 {{s.guest?'—':e.favoriteCount??0}} 人</text></view><view v-if="s.user.role!=='student'" class="stock-detail">总数 {{e.stock}} · 预留 {{e.reserved}} · 借出 {{e.borrowed}}</view></view></template></button></view>
          <view v-if="!s.loading&&!s.catalog.items.length" class="empty panel">没有找到符合条件的器材。</view><ServerPager :total="s.catalog.total" :page="s.catalog.page" :page-size="s.catalog.pageSize" :disabled="s.loading" :aria-disabled="s.loading" @change="page=>attempt(()=>loadCatalog(page))"/><template v-if="s.user.role==='student'">
            <view class="panel wish-directory-entry"><text class="block-title">没有找到想用的器材？</text><button role="button" class="btn secondary" @click="go('wishlist')">前往心愿清单 →</button></view>
            <view class="panel wish-directory-entry"><text class="block-title">已经与实验中心沟通好借用其他器材</text><button role="button" class="btn secondary" @click="go('otherBorrows')">其他借用登记 →</button></view>
          </template>
        </template>
        <BorrowManagementPanel v-else-if="s.user.role!=='student'&&['records','review','handover'].includes(s.page)" :key="s.user.id+':'+s.page" ref="borrowManagement" :mode="s.page" :user="s.user" :verified="s.verified" :pending="!!s.pending" @pending="s.pending=$event" @open="openRecord"/>
        <template v-else-if="['records','review','handover','pickupTasks'].includes(s.page)">
          <view class="section-top"><view><text class="page-title">{{heading}}</text><text class="muted">{{s.pickupProxyRecords?'选择本人收到的代领任务和领取时间。':s.user.role==='student'?'这里展示你的服务器借用记录，可在不同设备查询。':s.page==='review'?'贵重器材申请审批通过后预留库存，实际发放需现场确认。':'领取与归还均须现场核对身份、实物和配件。'}}</text></view><button role="button" class="btn secondary" :disabled="s.loading" :aria-disabled="s.loading" @click="attempt(()=>loadRecords(s.records.page))">刷新记录</button></view>
          <view v-if="s.page==='records'&&s.user.role==='student'" class="button-row" role="group" aria-label="借用记录范围">
            <button role="button" :class="['chip',{selected:!s.pickupProxyRecords}]" :aria-pressed="!s.pickupProxyRecords" @click="choosePickupScope(false)">我的申请</button>
            <button role="button" :class="['chip',{selected:s.pickupProxyRecords}]" :aria-pressed="s.pickupProxyRecords" @click="choosePickupScope(true)">代领任务</button>
          </view>
          <view class="chips record-filters"><button role="button" v-for="status in recordFilters" :key="status" :class="['chip',{selected:s.recordStatus===status}]" @click="chooseStatus(status)">{{status==='all'?'全部':labels[status]}}</button></view>
          <text v-if="s.loading" class="loading">正在加载记录…</text>
          <BorrowRecordsView v-if="s.page==='records'||s.page==='pickupTasks'" :records="s.records.items" :equipment-names="recordEquipment" :equipment-images="recordImages" :status-labels="labels" :viewer-role="s.user.role" :proxy-view="s.pickupProxyRecords" :verified="s.verified" :busy="s.busy" :pending="!!s.pending" :loading="s.loading" :view="recordView" :page-size="s.records.pageSize" @update:view="recordView=$event" @open="openRecord" @renew="renewFromList" @resources="openLoanResources" @duty="go('duty')"/><view v-else class="panel records-panel"><view v-for="record in s.records.items" :key="record.id"  :class="['record-row',{'record-history':['returned','cancelled','rejected','closed'].includes(record.status)}]" :data-record-id="record.id"><image class="record-thumb" :src="recordImages[record.equipmentId]||'/static/board-blue.png'" mode="aspectFit"/><view class="record-main"><view class="card-title-row"><text class="card-title">{{recordEquipment[record.equipmentId]||record.equipmentId}}</text><text :class="['status',record.status]">{{labels[record.status]}}</text></view><text class="muted">{{record.user}} · {{record.className||'未填写班级'}} · {{record.quantity}} 件</text><text v-if="record.pickupProxyName" class="small">代领人 {{record.pickupProxyName}} · {{record.pickupProxyAccount}}</text><text v-if="record.pickupSlot" class="small">代领时间 {{pickupSlotLabel(record.pickupSlot)}}</text><text v-else-if="record.pickupProxyId&&record.status==='approved'" class="small">待代领人选时间</text><text class="record-purpose">{{record.purpose}}</text><text class="small">申请时间 {{formatTime(record.createdAt)}}</text><text v-if="record.status!=='returning'&&record.dueDate" class="due-date">预计归还 {{record.dueDate}} · 已续借 {{record.renewCount}} 次</text><text v-if="record.status==='returning'&&record.returnDeadlineAt" class="due-date">申请归还后交回截止 {{formatTime(record.returnDeadlineAt)}}</text><button role="button" class="text-btn related-record" @click="relatedResources(record.equipmentId)">查看相关资料 ›</button></view><view class="record-actions-inline"><button v-if="s.user.role==='student'&&record.status==='borrowed'" role="button" class="btn" :disabled="s.busy||!s.verified||!record.allowedActions?.includes('renew')" @click="renewFromList(record)">{{renewLabel(record)}}</button><button role="button" class="btn secondary" @click="openRecord(record)">{{s.user.role==='student'?'查看与办理':record.status==='pending'?'审批申请':'查看与办理'}}</button></view></view><view v-if="!s.loading&&!s.records.items.length" class="empty">暂无{{s.recordStatus==='all'?'':labels[s.recordStatus]}}记录。</view></view><ServerPager :total="s.records.total" :page="s.records.page" :page-size="s.records.pageSize" :disabled="s.loading" :aria-disabled="s.loading" @change="page=>attempt(()=>loadRecords(page))"/>
          <DisplayExample v-if="s.page!=='records'&&!s.loading&&!s.error&&s.verified" :module="s.page" :empty="!s.records.total"/>
        </template>
        <template v-else-if="s.page==='duty'">
          <view class="section-top"><view><text class="page-title">器材借还值班时间</text><text class="muted">按公布的时段到场办理领取、归还，与实验室预约时间独立。</text></view><button role="button" v-if="s.user.role==='reviewer'" class="btn" :disabled="s.busy||!s.verified" :aria-disabled="s.busy||!s.verified" @click="openDuty">新增值班</button></view>
          <view class="panel"><view v-for="slot in s.duties.items" :key="slot.id" class="duty-row"><view class="duty-icon">◷</view><view><text class="card-title">{{slot.date}} {{slot.startTime.slice(0,5)}}—{{slot.endTime.slice(0,5)}}</text><text class="muted">{{slot.location}}</text></view><text class="status approved">已开放</text></view><view v-if="!s.duties.total" class="empty">暂无开放值班时间，请联系老师安排。</view></view><ServerPager :total="s.duties.total" :page="s.duties.page" :page-size="10" @change="page=>attempt(()=>loadDuties(page))"/>
          <DisplayExample v-if="!s.loading&&!s.error&&s.verified" module="duty" :empty="!s.duties.total"/>
        </template>
        <template v-else-if="s.page==='users'">
          <!-- #ifdef H5 -->
          <BorrowLimitControl listen-events :verified="s.verified" :blocked="s.busy||!!s.pending" @pending="s.pending=$event" @error="s.error=$event" @updated="attempt(()=>loadUsers(s.users.page))"/>
          <!-- #endif -->
          <view class="section-top"><view><text class="page-title">账号管理</text><text class="muted">管理员统一创建账号；停用会立即撤销该账号的登录会话。</text></view><view class="button-row account-create-actions"><button role="button" class="btn" :disabled="s.busy||!s.verified" :aria-disabled="s.busy||!s.verified" @click="openUser">新增账号</button><button role="button" class="btn secondary" :disabled="s.busy||!s.verified" :aria-disabled="s.busy||!s.verified" @click="openUser('teacher')">新增教师账号</button></view></view>
          <!-- #ifndef H5 -->
          <NativeRosterPanel :verified="s.verified" :locked="s.busy||!!s.pending" :refresh-token="nativeRosterRefresh" @pending="s.pending=$event" @toggle="toggleNativeRosterUser"/>
          <!-- #endif -->
          <!-- #ifdef H5 -->
          <view class="search-box user-search"><input v-model="s.userKeyword" class="field" placeholder="搜索账号或姓名" maxlength="100" @confirm="attempt(()=>loadUsers())"/><ChoiceField v-model="s.userAccountType" :options="[{value:'',label:'全部账号类型'},{value:'student',label:'学生账号'},{value:'teacher',label:'老师账号'},{value:'admin',label:'管理员账号'}]" @update:model-value="attempt(()=>loadUsers())"/><ChoiceField v-model="s.userEnabled" :options="[{value:'',label:'全部状态'},{value:'true',label:'已启用'},{value:'false',label:'已停用'}]" @update:model-value="attempt(()=>loadUsers())"/><button role="button" class="btn" @click="attempt(()=>loadUsers())">搜索账号</button></view>
          <view class="panel"><view v-for="user in s.users.items" :key="user.id" class="user-row"><view class="grow"><text class="card-title">{{user.name}} <text class="small">{{user.accountType==='teacher'?'教师':roles[user.role]}}</text></text><text class="muted">{{user.accountType==='teacher'?'工号：':''}}{{user.account}} · {{user.className||(user.accountType==='teacher'?'未填写部门':'未填写班级')}}</text></view><text :class="['status',user.enabled?'approved':'cancelled']">{{user.enabled?'已启用':'已停用'}}</text><!-- #ifndef H5 --><BorrowLimitControl :user-record="user" :verified="s.verified" :blocked="s.busy||!!s.pending" @pending="s.pending=$event" @error="s.error=$event" @updated="attempt(()=>loadUsers(s.users.page))"/><!-- #endif --><button role="button" v-if="user.id!==s.user.id" class="btn secondary small" :disabled="s.busy" :aria-disabled="s.busy" @click="toggleUser(user)">{{user.enabled?'停用':'启用'}}</button><text v-else class="small">当前账号</text></view><view v-if="!s.users.items.length" class="empty">没有找到账号。</view></view><ServerPager :total="s.users.total" :page="s.users.page" :page-size="10" :disabled="s.loading" :aria-disabled="s.loading" @change="page=>attempt(()=>loadUsers(page))"/>
          <DisplayExample v-if="!s.loading&&!s.error&&s.verified" module="users" :empty="!s.users.total"/>
          <!-- #endif -->
        </template>
        <template v-else-if="s.page==='profile'">
          <view class="section-top"><view><text class="page-title">个人资料</text><text class="muted">{{s.user.accountType==='teacher'?'姓名和工号由管理员维护，部门可自行修改。':'姓名和学号由管理员维护，班级与当前所在级可自行修改。'}}</text></view></view>
          <view class="panel profile-panel"><text class="small">当前未归还器材额度：{{s.user.borrowLimit??3}}件（由管理员维护）</text><view class="identity"><view class="avatar">{{s.user.name.slice(0,1)}}</view><view><text class="card-title">{{s.user.name}}</text><text class="muted">{{s.user.account}} · {{s.user.accountType==='teacher'?'教师':roles[s.user.role]}}</text></view></view><text v-if="s.user.accountType!=='teacher'&&s.user.enrollmentYear" class="small">入学年份：{{s.user.enrollmentYear}}年（管理员登记）</text><view class="field-group"><text>{{s.user.accountType==='teacher'?'部门':'班级'}}</text><input v-model="s.profile.className" class="field" :placeholder="s.user.accountType==='teacher'?'选填部门':'选填班级'" maxlength="100"/></view><view v-if="s.user.accountType!=='teacher'" class="field-group"><text>当前所在级</text><input v-model="s.profile.grade" class="field" placeholder="选填，例如2026级" maxlength="40"/></view><view class="field-group"><text>联系电话</text><input v-model="s.profile.phone" class="field" placeholder="选填联系电话" maxlength="32"/></view><view class="field-group"><text>邮箱</text><input v-model="s.profile.email" class="field" placeholder="选填邮箱" maxlength="200"/></view><button role="button" class="btn" :disabled="s.busy||!s.verified" :aria-disabled="s.busy||!s.verified" @click="saveProfile">保存资料</button></view>
        </template>
        <view v-if="['labs','bookings'].includes(s.page)" class="chips grouped-tabs"><button role="button" :class="['chip',{selected:s.page==='labs'}]" @click="go('labs')">可预约实验室</button><button role="button" :class="['chip',{selected:s.page==='bookings'}]" @click="go('bookings')">{{s.user.role==='student'?'我的预约':'预约记录'}}</button></view><view v-if="s.user.role==='reviewer'&&approvalNav.some(n=>n.id===s.page)" class="grouped-back"><button role="button" class="text-btn" @click="go('approvals')">‹ 审批中心</button></view><view v-if="managementNav.some(n=>n.id===s.page)" class="grouped-back"><button role="button" class="text-btn" @click="go('management')">‹ 管理中心</button></view>
        <MessageInbox v-if="s.page==='notifications'&&!s.guest" :key="s.user.id" :verified="s.verified" @pending="s.pending=$event" @unread="unreadMessages=$event"/>
        <ModulePanel v-if="s.page!=='notifications'&&moduleDefs[s.page]&&!(s.guest&&isGuestPrivatePage(s.page))" :key="s.user.id+':'+s.page" ref="modulePanel" :module="s.page" :user="s.user" :verified="s.verified" :equipment-filter="s.resourceEquipment" :category-filter="s.resourceCategory" :status-filter="bookingStatus" @pending="s.pending=$event"/>
        <view class="footer">口袋实验室·线上服务平台 <text>数据以服务器办理结果为准</text></view>
      </view>
    </view>
  </template>
  <view v-if="s.user" :class="['mobile-dock',{'mobile-dock--admin':s.user.role==='admin'}]" role="navigation" aria-label="主导航"><button v-for="n in mobileNav" :key="n.id" role="button" :class="['dock-item',{active:activePrimary(s.page,s.user.role)===n.id}]" @click="go(n.id)"><image :src="iconSource(n.icon)"/><text>{{n.name}}</text></button></view>
  <view v-if="s.user&&s.showRules" class="modal-mask login-rules">
    <view class="modal" role="dialog" aria-modal="true" aria-label="平台使用须知">
      <view class="modal-header"><text class="block-title">平台使用须知</text></view>
      <scroll-view scroll-y class="rules-scroll"><view class="modal-content"><RegulationDocument kind="usage" compact/></view></scroll-view>
      <view class="rules-footer"><button role="button" class="btn full" @click="s.showRules=false">我知道了</button></view>
    </view>
  </view>
  <view v-if="s.dialog&&!['equipment','record','other-record'].includes(s.dialog)&&s.user" class="modal-mask" @click.self="close"><view class="modal" role="dialog" aria-modal="true" :aria-label="dialogTitle"><view class="modal-header"><text class="block-title">{{dialogTitle}}</text><button role="button" class="close" :disabled="s.busy" :aria-disabled="s.busy" aria-label="关闭" @click="close">×</button></view><scroll-view scroll-y class="modal-scroll"><view class="modal-content">
    <template v-if="s.dialog==='apply'">
      <text class="card-title">{{s.selected.name}} · {{s.selected.model}}</text>
      <text class="muted">借用数量：1件；实际领取日起3个自然月。当前未归还额度：{{s.user.borrowLimit??3}}件。</text>
      <view class="field-group">
        <text>领取方式</text>
        <view class="pickup-mode-options" role="group" aria-label="领取方式">
          <button role="button" :class="['btn',{secondary:s.form.pickupMode!=='duty'}]" :aria-pressed="s.form.pickupMode==='duty'" @click="setPickupMode('duty')">按值班时间领取</button>
          <button role="button" :class="['btn',{secondary:s.form.pickupMode!=='agreed'}]" :aria-pressed="s.form.pickupMode==='agreed'" @click="setPickupMode('agreed')">预约其他时间</button>
        </view>
      </view>
      <text v-if="s.form.pickupMode==='agreed'" class="small">请提前与老师沟通领取时间。{{s.selected.requiresApproval?'提交后等待老师审批。':''}}</text>
      <checkbox-group v-if="!s.selected.requiresApproval&&s.form.pickupMode==='duty'" @change="togglePickupProxy"><label class="check-row"><checkbox value="proxy" :checked="s.form.proxyEnabled"/><text>请人代领</text></label></checkbox-group>
      <PickupProxyField v-if="s.form.proxyEnabled" :model-value="s.form.pickupProxyId" @update:modelValue="selectPickupProxy"/>
      <template v-else-if="s.form.pickupMode==='duty'">
        <view class="field-group"><text>领取值班时间</text><DutyPicker :model-value="s.form.slotId" :slots="s.form.slots" :loading="s.form.slotsLoading" :loaded="s.form.slotsLoaded" :error="s.form.slotsError" :warning="s.form.slotWarning" :disabled="s.busy||!s.verified||!!s.pending" @update:model-value="selectPickupSlot" @refresh="loadProxySlots"/></view>
        <view v-if="s.form.slotsLoaded&&!s.form.slotsLoading&&!s.form.slotsError&&!s.form.slots.length" class="warning">暂无有效值班时间，可选择预约其他时间并提前与老师沟通。</view>
      </template>
      <view v-if="!s.form.proxyEnabled" class="field-group"><text>联系电话</text><input :value="s.form.phone" class="field" placeholder="请输入联系电话" maxlength="32" @input="editBorrowPhone($event.detail.value)"/></view>
      <view class="field-group"><text>借用用途（选填）</text><textarea v-model="s.form.purpose" class="field textarea" placeholder="填写课程、实验或项目用途" maxlength="500"/><text class="small purpose-count">{{s.form.purpose.length}} / 500</text></view>
      <checkbox-group @change="s.agreed=$event.detail.value.includes('agreed')"><label class="check-row"><checkbox value="agreed" :checked="s.agreed"/><text>已阅读借用须知，将按时领取、爱护器材并配合归还验收。</text></label></checkbox-group>
      <button role="button" class="btn full" :disabled="s.busy||!s.verified||(s.form.pickupMode==='duty'&&!s.form.proxyEnabled&&(s.form.slotsLoading||!s.form.slotsLoaded||!!s.form.slotsError||!s.form.slotId))" :aria-disabled="s.busy||!s.verified||(s.form.pickupMode==='duty'&&!s.form.proxyEnabled&&(s.form.slotsLoading||!s.form.slotsLoaded||!!s.form.slotsError||!s.form.slotId))" @click="submitBorrow">{{s.busy?'正在提交…':reservationApply?'提交预约':'提交借用申请'}}</button>
    </template>
    <template v-else-if="s.dialog==='duty'"><view class="field-group"><text>值班日期</text><DateTimeField v-model="s.form.date" mode="date"/></view><view class="time-fields"><view class="field-group"><text>开始时间</text><DateTimeField v-model="s.form.startTime" mode="time"/></view><view class="field-group"><text>结束时间</text><DateTimeField v-model="s.form.endTime" mode="time"/></view></view><view class="field-group"><text>领取 / 归还地点</text><input v-model="s.form.location" class="field" placeholder="请填写值班地点" maxlength="200"/></view><button role="button" class="btn full" :disabled="s.busy" :aria-disabled="s.busy" @click="saveDuty">保存值班时间</button></template>
    <template v-else-if="s.dialog==='user'"><view class="field-group"><text>{{s.form.role==='teacher'?'工号 *':'学号 / 账号 *'}}</text><input v-model="s.form.account" class="field" :placeholder="s.form.role==='teacher'?'6位或8位数字，保留前导0':'2—32位字母、数字、下划线或连字符'" maxlength="32"/></view><view class="field-group"><text>姓名</text><input v-model="s.form.name" class="field" placeholder="请输入姓名" maxlength="80"/></view><view class="field-group" data-account-identity="true"><text>账号类型</text><ChoiceField v-model="s.form.role" :options="roleOptions.map(r=>({value:r.id,label:r.name}))"/></view><view class="field-group"><text>{{s.form.role==='teacher'?'部门':'班级'}}</text><input v-model="s.form.className" class="field" :placeholder="s.form.role==='teacher'?'选填部门':'选填班级'" maxlength="100"/></view><view class="field-group"><text>初始密码</text><input v-model="s.form.password" class="field" password :placeholder="s.form.role==='teacher'?'至少6个字符':'至少12个字符'" maxlength="128"/></view><text v-if="s.form.role==='teacher'" class="small">工号自动识别6位或8位，前导0保留。教师为普通用户；管理权限需另行授权。</text><text class="small">请通过安全方式将账号和初始密码交给本人。</text><button role="button" class="btn full" :disabled="s.busy" :aria-disabled="s.busy" @click="saveUser">创建账号</button></template>
    <view v-if="s.error" class="error modal-error" role="alert">{{s.error}}</view>
  </view></scroll-view></view></view>
</view>
</template>
<script setup>
import {computed,onMounted,onUnmounted,watch,ref,provide} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import {usePortal} from '../../services/portal.js';
import {api as authenticatedApi} from '../../services/api.js';
import {guestApi} from '../../services/guest-api.js';
import {createGuestPortalClient,createPortalContextClient,GUEST_MESSAGE,isGuestPrivatePage} from '../../services/guest-portal-client.mjs';
import ServerPager from '../../components/ServerPager.vue';
import ModulePanel from '../../components/ModulePanel.vue';
import ChoiceField from '../../components/ChoiceField.vue';
import AssetBarcodeScanner from '../../components/AssetBarcodeScanner.vue';
import DateTimeField from '../../components/DateTimeField.vue';
import PublicAnnouncements from '../../components/PublicAnnouncements.vue';
import MessageInbox from '../../components/MessageInbox.vue';
import WeeklyDutyPanel from '../../components/WeeklyDutyPanel.vue';
import RulesDocuments from '../../components/RulesDocuments.vue';
import RegulationDocument from '../../components/RegulationDocument.vue';
import {regulationKind} from '../../services/regulation-content.mjs';
import BrandIdentity from '../../components/BrandIdentity.vue';
import {formatBorrowDate,formatBorrowMinute} from '../../services/borrow-dates.mjs';
import WishlistPage from '../../components/WishlistPage.vue';
import OtherBorrowPage from '../../components/OtherBorrowPage.vue';
import BorrowManagementPanel from '../../components/BorrowManagementPanel.vue';
import BorrowLimitControl from '../../components/BorrowLimitControl.vue';
// #ifndef H5
import NativeRosterPanel from '../../components/NativeRosterPanel.vue';
// #endif
import LabBookingSummary from '../../components/LabBookingSummary.vue';
import BorrowRecordsView from '../../components/BorrowRecordsView.vue';
import DutyPicker from '../../components/DutyPicker.vue';
import PickupProxyField from '../../components/PickupProxyField.vue';
import DisplayExample from '../../components/DisplayExample.vue';
import {moduleDefs} from '../../services/modules.js';
import {primaryNavigation,mobileNavigation,activePrimary,approvalNavigation,managementNavigation,staffServices,iconSource} from '../../services/navigation.mjs';
import {renewLabel} from '../../services/loan-policy.mjs';
function guestActionEvent(){if(s.guest)showGuestNotice();}
function showGuestNotice(){uni.showToast({title:GUEST_MESSAGE,icon:'none',duration:2200});}
const guestClient=createGuestPortalClient({publicClient:guestApi,onDenied:showGuestNotice});
let portalState,portalEpoch=0;
const api=createPortalContextClient({getClient:()=>portalState?.guest?guestClient:authenticatedApi,getContext:()=>portalEpoch});
provide('portalClient',api);provide('portalGuest',()=>!!portalState?.guest);
const {s,isAdmin,nav,heading,labels,roles,init,login,logout,enterGuest,leaveGuest,go:portalGo,refresh,switchBorrowerView,loadCatalog,loadRecords,loadCounts,loadDuties,loadUsers,attempt,openEquipment,beginApply,setPickupProxy,setPickupMode,selectPickupProxy,selectPickupSlot,editBorrowPhone,submitBorrow,openRecord,loadProxySlots,act,retryPending,acknowledgePending,saveProfile,openDuty,saveDuty,openUser,saveUser,toggleUser,close,dispose}=usePortal(api,{onGuestDenied:showGuestNotice});
// #ifndef H5
const nativeRosterRefresh=ref(0);
async function toggleNativeRosterUser(user){await toggleUser(user);nativeRosterRefresh.value++;}
watch(()=>s.users.items,()=>{nativeRosterRefresh.value++;});
// #endif
portalState=s;
const unreadMessages=ref(0);
let unreadRevision=0;
async function refreshUnread(){
  const current=++unreadRevision,user=s.user?.id;
  if(s.guest||!user||!s.verified)return;
  try{
    const {data}=await api.request('/notifications?pageSize=1');
    if(current===unreadRevision&&s.user?.id===user&&!s.guest)unreadMessages.value=data.unreadCount;
  }catch{}
}
watch(()=>[s.user?.id,s.guest],()=>{unreadRevision++;unreadMessages.value=0;},{flush:'sync'});
watch(()=>[s.page,s.verified,s.user?.id],()=>{if(s.page==='home')refreshUnread();},{immediate:true});

const homeRuleKind=ref(null);
watch(()=>s.guest?'guest':(s.user?.id||'anonymous')+':'+(s.user?.role||''),()=>{portalEpoch++;homeRuleKind.value=null;},{flush:'sync'});
watch(()=>s.page,()=>{homeRuleKind.value=null;},{flush:'sync'});
const regulationPending=computed(()=>/^\/regulations\/(?:management|usage)\/content$/.test(s.pending?.path||''));
const guestPageAction=computed(()=>({records:'查看我的借用',bookings:'查看我的预约',favorites:'添加收藏',subscriptions:'订阅提醒',notifications:'查看我的消息',purchases:'申请购置',submissions:'提交作品',profile:'修改资料',otherBorrows:'登记其他借用'}[s.page]||'登录后操作'));
const primaryNav=computed(()=>primaryNavigation(s.user?.role));const mobileNav=computed(()=>mobileNavigation(s.user?.role));const approvalNav=computed(()=>approvalNavigation(s.user?.role));const managementNav=computed(()=>managementNavigation(s.user?.role));
const personalServices=computed(()=>[...(s.user?.role==='student'?[{id:'favorites',name:'我的收藏',icon:'check'},{id:'purchases',name:'购置申请',icon:'equipment'},{id:'wishlist',name:'心愿清单',icon:'heart'},{id:'submissions',name:'作品投稿',icon:'edit'},{id:'subscriptions',name:'有货提醒',icon:'clock'},{id:'notifications',name:'消息提醒',icon:'records'},{id:'works',name:'作品展示',icon:'showcase'}]:staffServices(s.user?.role)),...(s.guest||isAdmin.value?[]:[{id:'pickupTasks',name:'代领任务',icon:'people'}])]);
const staffShortcuts=computed(()=>s.user?.role==='reviewer'?staffServices('reviewer'):managementNav.value.filter(n=>['wishlist','inventory','users','resources','works','submissions','labs'].includes(n.id)));
const helpServices=[{id:'profile',name:'个人资料',icon:'user'},{id:'rules',name:'使用须知',icon:'info'},{id:'duty',name:'值班时间',icon:'clock'},{id:'resources',name:'学习资源',icon:'book'}];
const modulePanel=ref(null),otherBorrowPage=ref(null),borrowManagement=ref(null),labSummary=ref(null),bookingStatus=ref(''),ruleKind=ref('management');
async function openLabStatus(status){await go('bookings');bookingStatus.value=status;}
async function detailScanned(code){if(!s.selected)return;s.form.assetNumber=code;await act(can('issue')?'issue':'return-confirm',{direct:true});}
function openRules(kind){if(!['management','usage'].includes(kind))return;ruleKind.value=kind;uni.navigateTo({url:'/pages/regulation/read?kind='+kind,fail:()=>{s.error='阅读页面暂时无法打开，请稍后重试';}});}
function openHomeRules(kind){
  homeRuleKind.value=regulationKind(kind);
  if(homeRuleKind.value)uni.pageScrollTo({scrollTop:0,duration:0});
}
function closeHomeRules(){homeRuleKind.value=null;}
async function openStockedWish(id){await go('equipment');await openEquipment({id});}
async function go(page){homeRuleKind.value=null;if(s.guest&&isGuestPrivatePage(page))showGuestNotice();if(page!=='bookings')bookingStatus.value='';if(['resources','works'].includes(page)){s.resourceEquipment='';s.resourceCategory='';}const repeated=s.page===page;await portalGo(page);if(repeated){await modulePanel.value?.reload();await otherBorrowPage.value?.reload();await borrowManagement.value?.refresh();}}
async function refreshPage(){await refresh();await modulePanel.value?.reload();await otherBorrowPage.value?.reload();await borrowManagement.value?.refresh();await labSummary.value?.refresh();}
const pickupSlotLabel=slot=>slot.date+' '+slot.startTime.slice(0,5)+'—'+slot.endTime.slice(0,5)+' · '+slot.location;
async function refreshUnifiedLoans(){
  await attempt(()=>Promise.all([loadCounts(),loadRecords(s.records.page)]));
  await borrowManagement.value?.refresh();
}
async function otherBorrowCreated(){
  await portalGo('records',{recordStatus:'borrowed'});
  s.notice='借用已登记，可在我的借用中申请归还。';
  await attempt(()=>loadCounts());
}
const reservationApply=computed(()=>['CAT01','CAT03'].includes(s.selected?.id));
function togglePickupProxy(event){setPickupProxy(event.detail.value.includes('proxy'));}
const categories=['','单片机','FPGA','物联网','嵌入式','机器人','人工智能'];
const canSwitchBorrowerView=computed(()=>s.user?.canManage&&s.user.accountRole==='student');
const roleOptions=[{id:'student',name:'学生'},{id:'teacher',name:'教师（普通用户）'},{id:'admin',name:'管理员'}];
const actionNames={create:'提交申请',approve:'审批通过',reject:'拒绝申请',cancel:'取消领取','extend-pickup':'延长领取时间','expire-pickup':'超期未领自动取消','schedule-pickup':'确认代领时间',issue:'确认发放','return-request':'申请归还','return-confirm':'确认归还','return-confirm-direct':'现场确认归还','return-reject':'退回归还验收',renew:'申请续借','test-cleanup':'测试记录清理'};
const recordEquipment=ref({}),recordImages=ref({}),recordView=ref('cards');
const dialogTitle=computed(()=>({equipment:'器材详情',apply:reservationApply.value?'预约领取':'申请借用',record:'借用记录',duty:'新增值班',user:'新增账号'}[s.dialog]||''));
const recordFilters=computed(()=>s.page==='review'?['pending','cancelled']:s.page==='handover'?['approved','borrowed','returning','returned','cancelled','closed']:['all','pending','approved','borrowed','returning','returned','cancelled','closed']);
const recordHint=computed(()=>{
  if(s.selected?.status==='approved'&&s.selected.pickupProxyId){
    if(!s.selected.pickupSlot)return s.selected.pickupProxyId===s.user.id?'请先选择你可以到场代领的值班时间。':'器材已预留，等待代领人选择领取时间。';
    return '代领人请按所选值班时间到场，由老师核对代领人身份并发放器材。';
  }
  if(s.pickupProxyRecords&&s.selected?.pickupProxyId===s.user.id)return '本记录为代领任务，借用、续借和归还由原申请人处理。';
  if(s.selected?.pickupMode==='agreed'&&s.selected.status==='approved')return '请按与老师约定的时间到场领取，实际借期从发放时开始。';
  return ({pending:'申请正在等待老师审批，审批通过前不占库存。',approved:'已预留器材。请按值班时间到场，由老师确认发放；此时借期尚未开始。',borrowed:'新发放借期为3个月，首次续借2个月，第二次1个月；历史记录仍以显示的预计归还日期为准。结束使用后请申请归还并交回实物。',returning:'请于申请归还后的一周内交回器材。自行查看值班时间，带齐器材及配件到场，由老师验收。超过一周仍未验收归还将收到站内提醒；若已交回，请联系老师完成验收。申请归还不延长原借期，也不会恢复可借库存。',returned:'老师已完成完好验收，器材库存已恢复。',cancelled:'申请已取消，已有预留已释放。',rejected:'申请未通过，请查看下方办理原因。',closed:'异常已结单，请联系老师了解后续处理。'}[s.selected?.status]||'');
});
const recordActions=computed(()=>{const actions=s.selected?.allowedActions||[];return s.pickupProxyRecords?actions.filter(action=>action==='schedule-pickup'):actions;});
const can=action=>recordActions.value.includes(action);
function updateVerification(event){s.checkedIdentity=event.detail.value.includes('identity');s.checkedItems=event.detail.value.includes('items');}
function formatTime(value){if(!value)return '—';const d=new Date(value);return Number.isNaN(d.getTime())?value:d.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});}
function slotLabel(id){const slot=s.form.slots?.find(x=>x.id===id);return slot?`${slot.date} ${slot.startTime.slice(0,5)}—${slot.endTime.slice(0,5)} · ${slot.location}`:'';}
async function searchEquipment(){await attempt(()=>loadCatalog());}
async function chooseCategory(category){s.catalogFilter.category=category;await searchEquipment();}
async function chooseStatus(status){s.recordStatus=status;await attempt(()=>loadRecords());}
async function choosePickupScope(proxy){if(s.pickupProxyRecords===proxy)return;s.pickupProxyRecords=proxy;s.records.items=[];s.records.total=0;await chooseStatus('all');}
async function openStatus(status){await go(s.user.role==='reviewer'?(status==='pending'?'review':'handover'):'records');if(['records','review','handover'].includes(s.page)){s.recordStatus=status;if(s.user.role!=='student')await borrowManagement.value?.setStatus(status);else await attempt(()=>loadRecords());}}
watch(()=>s.user?.id,async id=>{if(!id){recordEquipment.value={};return;}try{const result=await api.catalog('/equipment?page=1&pageSize=100');if(s.user?.id===id){recordEquipment.value=Object.fromEntries(result.data.items.map(e=>[e.id,e.name+' · '+e.model]));recordImages.value=Object.fromEntries(result.data.items.map(e=>[e.id,e.image]));}}catch{}},{immediate:true});
watch(()=>s.records.items,async rows=>{const userId=s.user?.id;if(!userId)return;const ids=[...new Set(rows.map(row=>row.equipmentId).filter(Boolean))].filter(id=>!recordEquipment.value[id]);await Promise.all(ids.map(async id=>{try{const {data}=await api.request('/equipment/'+id);if(s.user?.id===userId){recordEquipment.value={...recordEquipment.value,[id]:data.name+' · '+data.model};recordImages.value={...recordImages.value,[id]:data.image};}}catch{}}));});

const related=ref({resources:[],works:[]}),relatedLoading=ref(false),relatedError=ref(''),favorited=ref(false);let relatedRequest=0;
watch(()=>s.dialog==='equipment'?s.selected?.id:null,async id=>{const request=++relatedRequest;if(!id)return;related.value={resources:[],works:[]};relatedError.value='';relatedLoading.value=true;favorited.value=false;try{const [resources,works]=await Promise.all([api.request('/resources?equipmentId='+encodeURIComponent(id)+'&pageSize=10'),api.request('/works?equipmentId='+encodeURIComponent(id)+'&pageSize=10')]);if(request!==relatedRequest)return;related.value={resources:resources.data.items,works:works.data.items};if(s.user?.role==='student'){let page=1,found=false,total=0,seen=0;do{const result=(await api.request('/favorites?pageSize=100&page='+page)).data;found=result.items.some(e=>e.id===id);total=result.total;seen+=result.items.length;page++;}while(!found&&seen<total&&page<=100);if(request===relatedRequest)favorited.value=found;}}catch(e){if(request===relatedRequest)relatedError.value=e.message||'相关内容暂时加载失败';}finally{if(request===relatedRequest)relatedLoading.value=false;}});
async function toggleFavorite(){if(s.guest){showGuestNotice();return;}if(s.busy||!s.verified||s.pending)return;s.busy=true;try{const id=s.selected.id;const value=!favorited.value;await api.mutate(value?'/favorites':'/favorites/'+id,value?{equipmentId:id}:undefined,{method:value?'POST':'DELETE'});favorited.value=value;s.selected=(await api.request('/equipment/'+id)).data;}catch(e){s.error=e.message;s.pending=api.pending();}finally{s.busy=false;}}
let loanResourceLookup=0;
async function openLoanResources(id){
  const revision=++loanResourceLookup;
  const account=s.user?.id;
  const current=()=>revision===loanResourceLookup&&s.page==='records'&&!s.dialog&&s.user?.id===account;
  try{
    const {data}=await api.request('/resources?equipmentId='+encodeURIComponent(id)+'&pageSize=1');
    if(!current())return;
    if(!data.items.length){uni.showToast({title:'暂无相关资料',icon:'none'});return;}
    s.resourceCategory='';
    s.resourceEquipment=id;
    await portalGo('resources');
  }catch{
    if(current())uni.showToast({title:'相关资料读取失败',icon:'none'});
  }
}
async function relatedResources(id){const item=s.selected?.id===id?s.selected:null;s.resourceEquipment='';s.resourceCategory=item?.category||'';await portalGo('resources');}
async function relatedWorks(id){await portalGo('works');s.resourceEquipment=id||'';s.resourceCategory='';}
async function renewFromList(record){await openRecord(record);if(s.selected?.id===record.id&&s.dialog==='record')await act('renew');}
async function setCatalogView(view){s.catalogView=view;await attempt(()=>loadCatalog());}

let requestedRules=false,requestedGuest=false;
onLoad(options=>{requestedRules=options?.section==='rules';requestedGuest=options?.guest==='1';});
let timer;onMounted(async()=>{
  // #ifdef H5
  window.addEventListener('pocket-lab-guest-action',guestActionEvent);
  // #endif
  await init();
  if(requestedGuest&&!s.user)enterGuest();
  if(requestedRules&&s.user)await go('rules');
  timer=setInterval(()=>{
  // #ifdef H5
  if(document.visibilityState!=='visible')return;
  // #endif
  if(s.page==='home')refreshUnread();
  if(s.user&&!s.busy&&!s.dialog&&!['profile','personal','management','approvals','rules'].includes(s.page))refresh({quiet:true});
},30000);});onUnmounted(()=>{clearInterval(timer);unreadRevision++;dispose();
  // #ifdef H5
  window.removeEventListener('pocket-lab-guest-action',guestActionEvent);
  // #endif
});
</script>
<style src="./live.css"></style>
<style src="./restore-v1.css"></style>
<style src="./college-refinement.css"></style>

<style>
.portal .auth-guest-entry{margin-top:22px;padding-top:20px;border-top:1px solid #dfe6f2;display:flex;flex-direction:column;gap:12px}.portal .auth-guest-entry>text{font-size:12px;color:#526887;line-height:1.6}.portal .auth-guest-entry .btn{color:#345da6;background:#f4f7fe}.guest-account-notice{display:flex;flex-direction:column;align-items:flex-start;gap:18px}
.portal .auth-intro .university-brand{align-items:center;text-align:center;align-self:flex-start;width:max-content;max-width:100%}
.portal .borrow-kind-label{display:inline-flex;align-items:center;justify-content:center;color:#344f7a;white-space:nowrap}
@media(max-width:760px){.portal .borrow-kind-label{font-size:12px}}
</style>

<style scoped>
.pickup-mode-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:8px}
.pickup-mode-options .btn{min-width:0;margin:0;white-space:normal}
.purpose-count{align-self:flex-end}
</style>

<style scoped>
.home-inbox{display:inline-flex;align-items:center;gap:8px;margin:0 0 12px}
.home-inbox-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#c52d41}
</style>
