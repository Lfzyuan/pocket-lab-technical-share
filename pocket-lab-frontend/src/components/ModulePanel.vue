<template>
<view class="module-panel" :aria-busy="busy||loading">
<view v-if="!pageDialog" class="module-overview">
  <view class="section-top"><view><text class="page-title">{{def.name}}</text><text class="muted">{{def.hint}}</text></view><button v-if="module==='labs'&&user.role==='student'" role="button" class="btn" :disabled="blocked" :aria-disabled="blocked" @click="openBookingForm()">申请预约</button><button v-if="def.create?.includes(user.role)" role="button" class="btn" :disabled="blocked" :aria-disabled="blocked" @click="create">{{module==='submissions'?'提交作品':module==='purchases'?'申请购置':module==='favorites'?'添加收藏':module==='subscriptions'?'订阅提醒':'新增'+def.name}}</button></view>
  <view v-if="error" class="error" role="alert">{{error}}</view><view v-if="notice" class="success" role="status">{{notice}}</view>
  <AssetImportPanel v-if="module==='inventory'&&user.role==='admin'" :disabled="blocked" @pending="$emit('pending',$event)"/>
  <template v-if="module==='importUsers'">
    <!-- #ifdef H5 -->
    <view class="student-import-slot" data-student-import="true"><text class="student-import-loading muted">正在载入名单导入工具…</text></view>
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <view class="panel" data-account-identity="true"><view class="button-row"><text class="block-title">账号导入</text><button role="button" class="btn secondary" :disabled="blocked||templateDownloading" @click="downloadImportTemplate">{{templateDownloading?'正在准备模版…':'账号导入模版'}}</button></view><textarea v-model="importText" class="field textarea import-area" :disabled="busy" aria-label="手动输入账号名单" placeholder="学号/工号,姓名,班级/院系,年级/老师,手机号" maxlength="1000000"/><view class="button-row"><button role="button" class="btn secondary" :disabled="blocked" :aria-disabled="blocked" @click="importUsers(true)">{{importReport?'重新校验预览':'校验预览'}}</button><button role="button" class="btn" :disabled="blocked||!importValid" :aria-disabled="blocked||!importValid" @click="importUsers(false)">确认导入</button></view><text v-if="importReport" class="muted">{{importReportLabel}}</text><view v-for="row in visibleImportErrors" :key="row.row" class="reason"><text>第{{row.row}}行：{{row.message}}</text><checkbox-group v-if="row.confirmationRequired||row.status==='confirmation'" @change="event=>setImportYearConfirmation(row,event.detail.value.length>0)"><label class="check-row"><checkbox value="confirmed" :checked="importConfirmedRows.includes(row.row)" :disabled="blocked||!importYearPlanId"/><text>已核实，采用表格填写的{{row.yearConflict.providedYear}}年（学号推断{{row.yearConflict.derivedYear}}年）。勾选后须重新校验。</text></label></checkbox-group></view><ServerPager v-if="importIssueRows.length" :total="importIssueRows.length" :page="importPage" :page-size="10" @change="importPage=$event"/></view>
    <!-- #endif -->
  </template>
  <template v-else-if="module==='statistics'">
    <view class="panel"><view class="button-row"><button v-for="p in periods" :key="p.id" role="button" :class="['chip',{selected:period===p.id}]" @click="period=p.id">{{p.name}}</button></view><view v-if="period==='custom'" class="time-fields"><DateTimeField v-model="from" mode="date"/><DateTimeField v-model="to" mode="date"/></view><button role="button" class="btn" :disabled="loading" :aria-disabled="loading" @click="loadStats(1)">查询统计</button><text class="muted">统计区间：{{stats.from||'—'}} 至 {{stats.to||'—'}}。待维修为当前存量。</text></view>
    <view class="stats-grid"><view v-for="(label,key) in statisticNames" :key="key" class="stat"><text class="stat-number">{{stats[key]??'—'}}</text><text class="muted">{{label}}</text></view></view><view class="panel"><text class="block-title">器材热度</text><view v-for="row in data.items" :key="row.id" class="record-row"><view><text class="card-title">{{row.name}} · {{row.model}}</text><text class="muted">收藏 {{row.favoriteCount}} 人 · 区间实际借出 {{row.issuedCount}} 件</text></view></view></view><ServerPager :total="data.total" :page="data.page" :page-size="10" @change="loadStats"/>
  </template>
  <template v-else>
    <view v-if="['resources','works'].includes(module)" class="resource-filters"><view class="chips"><button role="button" :class="['chip',{selected:!selectedEquipment&&!selectedCategory}]" @click="selectedEquipment='';selectedCategory='';load(1)">全部{{module==='works'?'作品':'资料'}}</button><button v-if="selectedEquipment" role="button" class="chip selected" @click="selectedEquipment='';load(1)">{{equipmentName(selectedEquipment)||'关联器材'}} ×</button><button v-for="category in resourceCategories" :key="category" role="button" :class="['chip',{selected:selectedCategory===category}]" @click="selectedEquipment='';selectedCategory=category;load(1)">{{category}}</button></view><view class="field-group compact-filter"><text>按关联器材筛选</text><ChoiceField v-model="selectedEquipment" :options="[{value:'',label:'全部器材及通用资料'},...equipment.map(e=>({value:e.id,label:e.name+' · '+e.model}))]" @update:model-value="load(1)"/></view></view>
    <view v-if="searchable" class="search-box"><input v-model="keyword" class="field" placeholder="搜索名称或型号" @confirm="load(1)"/><button role="button" class="btn" @click="load(1)">搜索</button></view>
    <view v-if="filters.length" class="chips record-filters"><button v-for="item in filters" :key="item" role="button" :class="['chip',{selected:status===item}]" @click="status=item;load(1)">{{item?statusNames[item]:'全部'}}</button></view>
    <text v-if="module==='notifications'" class="updated">共有 {{data.unreadCount||0}} 条未读消息</text><text v-if="loading" class="loading">正在加载服务器数据…</text>
    <view class="panel"><view v-for="row in data.items" :key="row.id" class="module-row" :data-module-record="row.id"><view class="card-title-row"><text class="card-title">{{row.name||row.title||row.labName||equipmentName(row.equipmentId)||def.name}}</text><text v-if="row.status" :class="['status',row.status]">{{statusNames[row.status]||row.status}}</text></view><view class="module-values"><text v-for="key in def.display" :key="key" v-show="row[key]!==null&&row[key]!==undefined&&row[key]!==''"><text class="value-label">{{fieldNames[key]||key}}：</text>{{value(key,row[key])}}</text></view>
      <view class="button-row module-actions">
        <button role="button" class="btn secondary small" @click="showDetail(row)">查看详情</button>
        <button v-if="def.edit?.includes(user.role)" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="edit(row)">编辑</button>
        <template v-if="module==='labs'"><button role="button" class="btn secondary small" @click="availability(row)">开放与余量</button><button v-if="user.role==='student'" role="button" class="btn small" :disabled="blocked" :aria-disabled="blocked" @click="book(row)">预约实验室</button><button v-if="user.role==='admin'" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="schedule(row)">设置开放时段</button><button v-if="user.role==='admin'" role="button" class="btn secondary small danger" :disabled="blocked" :aria-disabled="blocked" @click="removeLab(row)">删除实验室</button></template>
        <template v-if="module==='inventory'"><button role="button" class="btn small" :disabled="blocked" :aria-disabled="blocked" @click="adjust(row)">调整库存</button><button role="button" class="btn secondary small" @click="assets(row)">资产台账</button></template>
        <button v-for="action in row.allowedActions||[]" :key="action" role="button" class="btn small" :disabled="blocked" :aria-disabled="blocked" @click="actionForm(row,action)">{{actionNames[action]}}</button>
        <button v-if="module==='favorites'" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="removeFavorite(row)">取消收藏</button>
        <button v-if="module==='subscriptions'&&row.status!=='cancelled'" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="cancelSubscription(row)">取消订阅</button>
        <button v-if="module==='notifications'&&!row.read" role="button" class="btn small" :disabled="blocked" :aria-disabled="blocked" @click="markRead(row)">标记已读</button>
        <button v-if="['announcements','dutyManage'].includes(module)" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="toggle(row)">{{row.enabled?'停用':'启用'}}</button>
        <button v-if="module==='announcements'" role="button" class="btn secondary small" :disabled="blocked" :aria-disabled="blocked" @click="removeAnnouncement(row)">删除</button>
        <button v-if="module==='incidents'&&user.role==='admin'&&row.status==='repair'" role="button" class="btn small" :disabled="blocked" :aria-disabled="blocked" @click="actionForm(row,'repair')">维修验收恢复</button>
        <button v-if="module==='resources'&&(row.fileId||(isGuest&&row.hasFile))" role="button" class="btn secondary small" :disabled="busy" :aria-disabled="busy" @click="downloadResource(row)">下载资料</button>
        <button v-if="['works','submissions'].includes(module)&&row.fileId" role="button" class="btn secondary small" :disabled="busy" :aria-disabled="busy" @click="downloadAttachment(row)">下载附件</button>
      </view>
    </view><view v-if="!loading&&!data.items.length" class="empty">暂无{{def.name}}数据。</view></view><ServerPager :total="data.total" :page="data.page" :page-size="data.pageSize" :disabled="loading" @change="load"/>
  </template>
  <DisplayExample v-if="dataReady&&!loading&&!error" :module="module" :empty="data.total===0"/>
</view>
  <view v-if="dialog" :class="pageDialog?'content-page':'modal-mask'" @click.self="!pageDialog&&close()"><view :class="pageDialog?'content-document':'modal'" :role="pageDialog?'region':'dialog'" :aria-modal="pageDialog?undefined:true" :aria-label="title"><view class="modal-header"><view><button v-if="pageDialog" role="button" class="text-btn page-back" :disabled="busy" @click="close">‹ 返回{{def.name}}</button><text class="block-title">{{title}}</text></view><button v-if="!pageDialog" role="button" class="close" aria-label="关闭" :disabled="busy" :aria-disabled="busy" @click="close">×</button></view><scroll-view :scroll-y="!pageDialog" :class="pageDialog?'content-page-body':'modal-scroll'"><view class="modal-content">
    <view v-if="dialog==='form'" :class="{'booking-form-grid':fields.some(field=>field.key==='members')}">
      <text v-if="formHint" class="muted">{{formHint}}</text><view v-if="['works','submissions'].includes(module)" class="author-summary"><text class="block-title">作者信息</text><text>{{user.name}} · {{user.account}}</text><text class="muted">{{form.privacyMode==='anonymous'?'公开显示为“匿名作者”':form.privacyMode==='public'?'实名展示作者资料':'请选择作品发布方式'}}</text><text class="small">选择“公开联系方式”后展示已填写的联系方式。匿名发布隐藏所有作者身份；请自行检查媒体及附件中的署名。</text></view><view v-for="field in fields" :key="field.key" :class="['field-group',{'form-field-wide':['textarea','team','file','image','labSchedule','labHalfDays','multiChoice','privacy'].includes(field.type)}]"><text>{{field.label}}</text>
        <textarea v-if="field.type==='textarea'" v-model="form[field.key]" :focus="field.key==='purpose'&&purposeFocus" @blur="purposeFocus=false" class="field textarea" :maxlength="field.max||5000" :placeholder="field.label"/>
        <DateTimeField v-else-if="field.type==='date'||field.type==='time'" v-model="form[field.key]" :mode="field.type"/>
        <view v-else-if="module==='inventory'&&field.key==='category'">
          <input v-model="form.category" class="field" maxlength="80" placeholder="填写器材分类"/>
          <text v-if="otherEquipment" class="muted">此分类需要审批，并按实际资产编号办理借还。</text>
        </view>
        <radio-group v-else-if="field.type==='privacy'" class="work-privacy-options"
          @change="form.privacyMode=$event.detail.value;form.privacyTouched=true">
          <label v-for="option in [{value:'anonymous',label:'匿名发布'},{value:'public',label:'公开联系方式'}]" :key="option.value"
            :class="['work-privacy-option',{selected:form.privacyMode===option.value}]">
            <radio :value="option.value" :checked="form.privacyMode===option.value" :disabled="blocked"/><text>{{option.label}}</text>
          </label>
          <text class="small privacy-hint">{{form.privacyMode==='anonymous'?'公开显示匿名作者，隐藏作者身份和联系方式。':form.privacyMode==='public'?'实名展示作者资料和已填写的联系方式。':extra.version!==undefined?'当前记录保留原发布设置，可在这里重新选择。':'请选择一种发布方式后提交。'}}</text>
        </radio-group>
        <checkbox-group v-else-if="field.type==='flag'" @change="form[field.key]=$event.detail.value.includes('yes')"><label class="check-row"><checkbox value="yes" :checked="!!form[field.key]||otherEquipmentControls.includes(field.key)" :disabled="otherEquipmentControls.includes(field.key)"/><text>{{field.label}}</text></label></checkbox-group>
        <view v-else-if="field.type==='multiChoice'" class="lab-period-options"><checkbox-group @change="form[field.key]=$event.detail.value"><label v-for="period in field.options" :key="period.value" class="check-row"><checkbox :value="period.value" :checked="(form[field.key]||[]).includes(period.value)"/><text>{{period.label}}</text></label></checkbox-group></view>
        <view v-else-if="field.type==='labSchedule'" class="lab-opening-plan">
          <checkbox-group @change="form.applyOpeningSchedule=$event.detail.value.includes('apply')"><label class="check-row"><checkbox value="apply" :checked="form.applyOpeningSchedule"/><text>{{extra.version===undefined?'同时设置开放时间':'修改开放时间（替换未来开放安排）'}}</text></label></checkbox-group>
          <view v-if="form.applyOpeningSchedule">
            <view class="time-fields"><view><text class="small">开放开始日期</text><DateTimeField v-model="form.openingSchedule.dateFrom" mode="date"/></view><view><text class="small">开放结束日期（含当天）</text><DateTimeField v-model="form.openingSchedule.dateTo" mode="date"/></view></view>
            <checkbox-group class="lab-period-options" @change="form.openingSchedule.slotIds=$event.detail.value"><label v-for="period in labPeriods" :key="period.value" class="check-row"><checkbox :value="period.value" :checked="form.openingSchedule.slotIds.includes(period.value)"/><text>{{period.label}}</text></label></checkbox-group>
            <text class="muted">上午 08:00–13:00 · 下午 13:00–18:00 · 晚上 18:00–22:00。日期范围内每天开放所选时段。</text>
          </view>
        </view>
        <ChoiceField v-else-if="['choice','equipment','resourceCategory'].includes(field.type)" v-model="form[field.key]" :options="options(field)" :placeholder="field.placeholder"/>
        <view v-else-if="field.type==='image'" class="equipment-photo"><image v-if="form[field.key]" :src="imagePreview||form[field.key]" class="photo-preview" mode="aspectFit" aria-label="器材图片预览"/><text class="muted">PNG、JPEG、WebP，图片小于30MB。确认提交后更新器材图片。</text><button role="button" class="btn secondary small" :disabled="busy" :aria-disabled="busy" @click="uploadImage(field.key)">{{busy?'正在处理…':form[field.key]?'更换器材图片':'选择并上传器材图片'}}</button><button v-if="form[field.key]" role="button" class="text-btn" :disabled="busy" @click="removeImage(field.key)">移除器材图片</button></view>
        <view v-else-if="field.type==='file'"><text class="muted">{{form[field.key]?'已关联附件':'尚未选择附件'}}（图片小于30MB，其他附件小于5GB）</text><button role="button" class="btn secondary small" :disabled="busy" :aria-disabled="busy" @click="upload(field.key)">选择并上传文件</button><button v-if="form[field.key]" role="button" class="text-btn" @click="form[field.key]=''">移除附件关联</button></view>
        <LabHalfDayGrid v-else-if="field.type==='labHalfDays'" v-model="form[field.key]" :lab-id="form.labId" :participants="members.length+1" :disabled="blocked" @validity="bookingValid=$event"/>
        <TeacherChoice v-else-if="field.type==='teacher'" v-model="form[field.key]" :required="field.required" :disabled="blocked" :selected-name="form.teacherLabel" @update:model-value="form.teacherLabel='';form.teacherChanged=true" @validity="teacherValid=$event"/>
        <MemberFields v-else-if="field.type==='team'" v-model="members" :current-user="user"/>
        <input v-else v-model="form[field.key]" :type="field.type==='number'?'number':'text'" class="field" :placeholder="field.label" :maxlength="field.max||1000"/>
      </view><button role="button" class="btn full" :disabled="blocked||bookingUnavailable" :aria-disabled="blocked||bookingUnavailable" @click="submit">{{bookingUnavailable?'暂无可预约实验室':busy?'正在提交…':'确认提交'}}</button>
    </view>
    <view v-else-if="dialog==='detail'&&['works','submissions'].includes(module)" class="work-document">
      <view class="work-document-heading"><text class="detail-title">{{detail.name||'作品详情'}}</text><text v-if="detail.status" :class="['status',detail.status]">{{statusNames[detail.status]||detail.status}}</text></view>
      <view class="work-section"><text class="block-title">作品介绍</text><view v-if="mediaKind" class="work-media"><image v-if="mediaKind==='image'" :src="detail.media" mode="widthFix"/><video v-else :src="detail.media" controls/></view><text class="work-prose">{{detail.desc||detail.description||'暂未填写作品介绍'}}</text><text v-if="detail.equipmentId" class="muted">关联器材：{{equipmentName(detail.equipmentId)||detail.equipmentId}}</text><button v-if="detail.media&&!mediaKind" role="button" class="text-btn" @click="openMedia(detail.media)">查看媒体链接 ↗</button></view>
      <view class="work-section"><text class="block-title">附件</text><text class="muted">{{detail.fileId?'作者提供的作品资料，按当前账号权限下载。':'作者尚未提供附件。'}}</text><button v-if="detail.fileId" role="button" class="btn secondary" :disabled="busy" @click="downloadAttachment(detail)">下载作品附件</button></view>
      <view class="work-section"><text class="block-title">作者</text><text class="work-prose">{{detail.anonymous?'匿名作者':detail.author||'未提供作者姓名'}}</text><template v-if="!detail.anonymous||module==='submissions'"><text v-if="detail.authorAccount" class="muted">学号：{{detail.authorAccount}}</text><text v-if="detail.authorClassName" class="muted">班级：{{detail.authorClassName}}</text><text v-if="detail.teacherName" class="muted">指导老师：{{detail.teacherName}}</text><text v-if="detail.authorContact&&(detail.contactPublic||module==='submissions')" class="muted">联系方式：{{detail.authorContact}}</text></template><text v-if="detail.createdAt" class="muted">提交时间：{{detail.createdAt}}</text></view>
      <view class="work-section"><text class="block-title">线下展示（选填）</text><text class="work-prose">{{detail.location||'暂未提供线下展示位置'}}</text></view>
      <view v-if="detail.reason" class="record-hint">审核说明：{{detail.reason}}</view>
    </view>
    <view v-else-if="dialog==='detail'" class="detail-lines"><view v-if="['works','submissions'].includes(module)&&mediaKind" class="work-media"><image v-if="mediaKind==='image'" :src="detail.media" mode="widthFix"/><video v-else-if="mediaKind==='video'" :src="detail.media" controls/></view><text class="detail-title">{{detail.name||detail.title||detail.labName||def.name}}</text><text v-for="key in detailKeys" :key="key"><text class="value-label">{{fieldNames[key]||key}}：</text>{{value(key,detail[key])}}</text><button v-if="['works','submissions'].includes(module)&&detail.fileId" role="button" class="btn secondary" :disabled="busy" @click="downloadAttachment(detail)">下载作品附件</button><button v-if="module==='resources'&&(detail.fileId||(isGuest&&detail.hasFile))" role="button" class="btn" :disabled="busy" @click="downloadResource(detail)">下载资料</button><text v-if="detail.status">状态：{{statusNames[detail.status]||detail.status}}</text><view v-if="detail.members"><text class="block-title">团队成员（{{detail.members.length}}人）</text><text v-for="person in detail.members" :key="person.account" class="muted">{{person.name}} · {{person.account}} · {{person.affiliation}}</text></view><view v-if="detail.cells"><text class="block-title">预约时段</text><text v-for="cell in detail.cells" :key="cell.date+cell.slotId" class="muted booking-time">{{cell.date}} {{cell.startTime}}—{{cell.endTime}}</text></view><view v-if="module==='bookings'&&detail.allowedActions?.length" class="button-row">
        <button v-for="action in detail.allowedActions" :key="action" role="button" class="btn small" :disabled="blocked" @click="actionForm(detail,action)">{{actionNames[action]}}</button>
      </view></view>
    <LabHalfDayGrid v-else-if="dialog==='availability'" :lab-id="availabilityLab" view-only/>
    <view v-else-if="dialog==='assets'"><text class="muted">器材ID：{{assetEquipment}}</text><view v-for="asset in assetData.items" :key="asset.assetNumber" class="module-row"><text class="card-title">{{asset.assetNumber}}</text><text class="muted">{{statusNames[asset.status]||asset.status}}</text></view><text v-if="!assetData.total" class="muted">尚未录入资产编号。可在发放时登记，管理员也可批量导入。</text><ServerPager :total="assetData.total" :page="assetData.page" :page-size="10" @change="assetPage"/></view>
    <view v-if="error" class="error" role="alert">{{error}}</view>
  </view></scroll-view></view></view>
</view>
</template>
<script setup>
import {inject,ref,reactive,computed,onMounted,onUnmounted,watch,nextTick} from 'vue';import {api as defaultApi} from '../services/api.js';
const api=inject('portalClient',defaultApi);import {moduleDefs,statusNames,fieldNames,bookingFields,field,choice,labPeriods,OTHER_EQUIPMENT_CATEGORY} from '../services/modules.js';import {chooseAndUpload,downloadFile} from '../services/files.js';import ServerPager from './ServerPager.vue';import ChoiceField from './ChoiceField.vue';import DateTimeField from './DateTimeField.vue';import DisplayExample from './DisplayExample.vue';import MemberFields from './MemberFields.vue';
import LabHalfDayGrid from './LabHalfDayGrid.vue';
import TeacherChoice from './TeacherChoice.vue';
import AssetImportPanel from './AssetImportPanel.vue';
import {parseImportText,missingRequiredRows} from '../services/roster-input.mjs';
import {downloadAccountTemplate} from '../services/account-template.mjs';
const props=defineProps({module:String,user:Object,verified:Boolean,equipmentFilter:String,categoryFilter:String,statusFilter:String,guest:Boolean});const emit=defineEmits(['pending']);
const portalGuest=inject('portalGuest',()=>false),isGuest=computed(()=>props.guest||props.user?.guest||portalGuest());
function guestAction(){if(!isGuest.value)return false;uni.showToast({title:'当前为游客模式，请登录后操作',icon:'none'});return true;}
const teacherValid=ref(true),bookingValid=ref(false);
const def=computed(()=>moduleDefs[props.module]),data=reactive({items:[],total:0,page:1,pageSize:10,unreadCount:0}),busy=ref(false),loading=ref(false),error=ref(''),notice=ref(''),dialog=ref(''),title=ref(''),fields=ref([]),form=reactive({}),detail=ref({}),equipment=ref([]),members=ref([]),keyword=ref(''),status=ref(props.statusFilter||''),formHint=ref(''),availabilityLab=ref(''),assetData=reactive({items:[],total:0,page:1}),assetEquipment=ref(''),selectedEquipment=ref(props.equipmentFilter||''),selectedCategory=ref(props.categoryFilter||''),resourceCategories=ref([]),resourceWriteCategories=ref([]);
let target='',method='POST',extra={};let alive=true,loadRevision=0,importPollTimer;onUnmounted(()=>{alive=false;loadRevision++;clearTimeout(importPollTimer);});
watch(()=>props.statusFilter,value=>{status.value=value||'';load(1);});const blocked=computed(()=>!isGuest.value&&(busy.value||loading.value||!props.verified||!!api.pending()));
const otherEquipment=computed(()=>props.module==='inventory'&&form.category?.trim()===OTHER_EQUIPMENT_CATEGORY);
const otherEquipmentControls=computed(()=>otherEquipment.value?['requiresApproval','requiresAsset']:[]);
const pageDialog=computed(()=>!!dialog.value&&['labs','bookings','works','submissions','resources'].includes(props.module));
const purposeFocus=ref(false);
const bookingUnavailable=computed(()=>dialog.value==='form'&&fields.value.some(f=>f.key==='labId'&&!f.options?.some(option=>option.value)));
watch(dialog,()=>{if(pageDialog.value)uni.pageScrollTo({scrollTop:0,duration:0});});
const dataReady=ref(false);
const imagePreview=ref('');
function clearImagePreview(){if(imagePreview.value){URL.revokeObjectURL(imagePreview.value);imagePreview.value='';}}
function removeImage(key){clearImagePreview();form[key]='';}
async function uploadImage(key){if(guestAction())return;if(blocked.value)return;busy.value=true;await safely(async()=>{const file=await chooseAndUpload({imagesOnly:true});if(file){clearImagePreview();imagePreview.value=file.previewUrl||'';form[key]='/api/files/'+file.id+'/image';}});busy.value=false;}
watch(dialog,clearImagePreview);onUnmounted(clearImagePreview);
const actionNames={approve:'审批通过',reject:'拒绝申请',cancel:'取消预约',receive:'验收到货入库',repair:'维修验收恢复'};
const searchable=computed(()=>['inventory','works','resources'].includes(props.module));
const filters=computed(()=>({bookings:['','pending','approved','rejected','cancelled'],purchases:['','pending','approved','rejected','received'],submissions:['','pending','published','rejected'],incidents:['','repair','writtenOff','resolved']}[props.module]||[]));
const mediaKind=computed(()=>{const url=detail.value.media||'';if(!/^https?:\/\//i.test(url))return '';if(/\.(png|jpe?g|webp|gif)(?:[?#]|$)/i.test(url))return 'image';if(/\.(mp4|webm|ogg)(?:[?#]|$)/i.test(url))return 'video';return '';});
watch(()=>props.categoryFilter,value=>{selectedCategory.value=value||'';if(['resources','works'].includes(props.module))load(1);});
watch(()=>form.equipmentId,id=>{if(props.module==='resources'&&dialog.value==='form'&&id){const linked=equipment.value.find(e=>e.id===id);if(linked)form.category=linked.category||'通用资料';}});
watch(()=>props.equipmentFilter,value=>{selectedEquipment.value=value||'';if(['resources','works'].includes(props.module))load(1);});
const detailKeys=computed(()=>[...new Set([...(def.value.display||[]),...(props.module==='bookings'?['account','labLocation','createdAt','reviewedByName','reviewedAt']:[]),'reason','teacherName'])].filter(key=>!(key==='media'&&mediaKind.value)&&detail.value[key]!==undefined&&detail.value[key]!==null&&detail.value[key]!==''));
function openMedia(url){if(!/^https?:\/\/[^\s]+$/i.test(url))return;
 // #ifdef H5
 window.open(url,'_blank','noopener,noreferrer');return;
 // #endif
 // #ifndef H5
 uni.setClipboardData({data:url});
 // #endif
}
function showDetail(row){detail.value=row;title.value=def.value.name+'详情';dialog.value='detail';}
function equipmentName(id){return equipment.value.find(e=>e.id===id)?.name;}
function value(key,v){if(typeof v==='boolean')return v?'是':'否';if(key==='equipmentId')return equipmentName(v)||v;if(key==='slotIds'||key==='openingSlotIds')return (Array.isArray(v)?v:String(v).split(',')).filter(Boolean).map(id=>value('slotId',id)).join('、');if(key==='slotId')return {B1:'上午',B2:'下午',B3:'晚上',S5:'19:00—20:40',S1:'08:00—09:40',S2:'10:00—11:40',S3:'14:00—15:40',S4:'16:00—17:40',AM:'上午（旧预约）',PM:'下午（旧预约）',ALL:'全天（旧预约）'}[v]||v;if(key==='kind')return {damaged:'损坏',lost:'丢失',retired:'报废',discrepancy:'盘亏'}[v]||v;if(key==='mode')return v==='add'?'入库':'移出';return String(v);}
function query(values){return Object.entries(values).filter(([,v])=>v!==''&&v!==undefined).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&');}
async function safely(fn){error.value='';try{return await fn();}catch(e){error.value=e.message||'请求失败，请稍后重试';emit('pending',api.pending());return null;}}
async function load(page=1){if(['statistics','importUsers'].includes(props.module))return;const revision=++loadRevision;loading.value=true;error.value='';try{const {data:result}=await api.request(def.value.path+'?'+query({page,pageSize:10,...def.value.query,...(status.value?{status:status.value}:{}),...(keyword.value?{keyword:keyword.value}:{}),...(['resources','works'].includes(props.module)&&selectedEquipment.value?{equipmentId:selectedEquipment.value}:{}),...(['resources','works'].includes(props.module)&&selectedCategory.value?{category:selectedCategory.value}:{})}));if(!alive||revision!==loadRevision)return;if(page>1&&!result.items.length)return load(Math.max(1,Math.ceil(result.total/10)));Object.assign(data,result);dataReady.value=true;}catch(e){if(alive&&revision===loadRevision){error.value=e.message||'请求失败';emit('pending',api.pending());}}finally{if(alive&&revision===loadRevision)loading.value=false;}}
function options(f){if(f.type==='resourceCategory'){const categories=[...new Set([...resourceWriteCategories.value,...(form.category?[form.category]:[])])];return [{value:'',label:'自动按关联器材分类 / 通用资料'},...categories.map(value=>({value,label:value}))];}return f.type==='equipment'?[{value:'',label:'请选择器材'},...equipment.value.map(e=>({value:e.id,label:e.name+' · '+e.model+'（可借'+e.available+'）'}))]:f.options;}
function showForm(name,fs,path,verb='POST',initial={},fixed={},hint=''){title.value=name;fields.value=fs;target=path;method=verb;extra=fixed;for(const key of Object.keys(form))delete form[key];for(const f of fs)form[f.key]=initial[f.key]??(f.type==='flag'?(f.default??true):f.type==='number'?1:['multiChoice','labHalfDays'].includes(f.type)?[]:'');if(fs.some(f=>f.type==='labSchedule')){form.applyOpeningSchedule=initial.applyOpeningSchedule??initial.version===undefined;form.openingSchedule={mode:'halfDay',dateFrom:initial.openingHalfDayDateFrom||today(),dateTo:initial.openingHalfDayDateTo||dateAfter(6),slotIds:Array.isArray(initial.openingHalfDaySlotIds)?[...initial.openingHalfDaySlotIds]:String(initial.openingHalfDaySlotIds||'').split(',').filter(Boolean)};}form.teacherLabel=initial.teacherName||'';form.teacherChanged=false;teacherValid.value=true;bookingValid.value=false;form.privacyMode=initial.anonymous?'anonymous':initial.contactPublic?'public':'';form.privacyTouched=false;members.value=[];formHint.value=hint;purposeFocus.value=false;error.value='';dialog.value='form';}
function create(){if(guestAction())return;const fs=props.module==='dutyManage'?def.value.fields.filter(f=>f.key!=='enabled'):def.value.fields;showForm('新增'+def.value.name,fs,def.value.writePath||def.value.path,'POST',props.module==='inventory'?{stock:0,requiresApproval:false,requiresAsset:false}:['works','submissions'].includes(props.module)?{authorClassName:props.user.className||'',contactPublic:false,anonymous:false}:{});}
function edit(row){if(guestAction())return;const fs=props.module==='inventory'?def.value.fields.filter(f=>f.key!=='stock'):def.value.fields;const initial={...row,applyOpeningSchedule:false};for(const f of fs)if(f.type==='time')initial[f.key]=initial[f.key]?.slice(0,5);showForm('编辑'+def.value.name,fs,(def.value.writePath||def.value.path)+'/'+row.id,'PUT',initial,{version:row.version});}
const dateAfter=days=>new Date(Date.now()+8*3600000+days*86400000).toISOString().slice(0,10),today=()=>dateAfter(0);
async function openBookingForm(selected){if(guestAction())return;
  if(blocked.value)return;
  busy.value=true;
  await safely(async()=>{
    const laboratories=new Map();let page=1;
    for(;;){const result=(await api.request('/labs?page='+page+'&pageSize=100')).data;for(const row of result.items)laboratories.set(row.id,row);if(!result.items.length||page*100>=result.total)break;page++;}
    const choices=[...laboratories.values()].map(row=>[row.id,row.name+' · '+row.location]);
    const labField={...choice('labId','实验室',choices),placeholder:'请选择实验室'};
    showForm('实验室预约申请',[labField,...bookingFields],'/lab-bookings','POST',{labId:laboratories.has(selected?.id)?selected.id:'',cells:[]});
    if(!laboratories.size)error.value='暂无可预约实验室';
  });
  busy.value=false;
}
function book(row){return openBookingForm(row);}
function schedule(row){if(guestAction())return;if(props.user.role!=='admin')return;showForm('设置开放时段',[field('dateFrom','开放开始日期','date'),field('dateTo','开放结束日期（含当天）','date'),field('slotIds','每天的时段（可多选）','multiChoice',{options:labPeriods}),field('enabled','开放所选时段','flag')],'/labs/'+row.id+'/schedule-range','PUT',{dateFrom:today(),dateTo:dateAfter(6),slotIds:['B1'],enabled:true},{},row.name+'。勾选时开放这些时段；取消勾选则关闭这些时段，已有批准预约时不能直接关闭。');}
function adjust(row){if(guestAction())return;showForm('调整库存',[choice('mode','变更方式',[['add','入库'],['remove','移出']]),field('count','数量','number'),field('reason','变动原因','textarea')],'/equipment/'+row.id+'/stock-adjustments','POST',{mode:'add',count:1},{version:row.version});}
function actionForm(row,action){if(guestAction())return;const fs=action==='reject'?[field('reason','拒绝原因','textarea')]:action==='approve'&&props.module==='bookings'?[field('temporaryPassword','本次预约临时密码（必填）')]:action==='receive'?[field('equipmentId','实际入库器材','equipment'),field('quantity','验收到货数量（1—100）','number')]:[];showForm(actionNames[action],fs,def.value.path+'/'+row.id+'/'+action,'POST',{}, {version:row.version},action==='receive'?'请先核实实物已到货；新型号先在库存维护创建条目。':action==='approve'&&props.module==='bookings'?'服务器将复检时段和成员冲突。审批人与临时密码仅发送给申请人的站内消息，密码有效范围为本次预约日期及时段。':'确认办理这条记录？结果由服务器校验。');}
async function submit(){if(guestAction())return;if(blocked.value||bookingUnavailable.value)return;if(fields.value.some(f=>f.key==='labId')){
  if(!form.labId){error.value='请选择实验室';return;}
  const purpose=form.purpose.trim();
  if(!purpose||purpose.length>500){
    error.value=purpose?'实验内容不能超过500字':'请填写实验内容';
    purposeFocus.value=false;
    await nextTick();
    purposeFocus.value=true;
    return;
  }
}
if(!teacherValid.value){error.value='请填写并匹配平台已有的指导老师';return;}const body={...extra};for(const f of fields.value){if(f.type==='team')body.members=members.value.filter(m=>m.name||m.account).map(m=>({name:m.name,account:m.account}));else if(f.type==='labSchedule'){if(form.applyOpeningSchedule)body.openingSchedule={...form.openingSchedule,slotIds:[...form.openingSchedule.slotIds]};}else if(f.type==='privacy'){
  if(extra.version===undefined||form.privacyTouched){
    if(!['anonymous','public'].includes(form.privacyMode)){error.value='请选择发布方式';return;}
    body.anonymous=form.privacyMode==='anonymous';body.contactPublic=form.privacyMode==='public';
  }
}else if(f.type==='teacher'){if(f.required&&!form[f.key]){error.value='请填写并匹配指导老师';return;}if(f.required||form.teacherChanged)body.teacherUserId=form[f.key]||null;}
else body[f.key]=f.type==='number'?Number(form[f.key]):form[f.key];}
if(fields.value.some(f=>f.type==='labHalfDays')&&(!body.cells?.length||!bookingValid.value)){error.value='请核对预约时段和同行人数后提交';return;}
if(fields.value.some(f=>f.type==='multiChoice')&&!body.slotIds?.length){error.value='请至少选择一个时段';return;}if(body.openingSchedule&&(!body.openingSchedule.dateFrom||!body.openingSchedule.dateTo||body.openingSchedule.dateTo<body.openingSchedule.dateFrom)){error.value='请填写有效的开放日期范围';return;}for(const key of otherEquipmentControls.value)body[key]=true;await save(target,body,method);}
async function save(path,body,verb='POST'){if(guestAction())return;if(blocked.value)return;busy.value=true;await safely(async()=>{await api.mutate(path,body,{method:verb});emit('pending',api.pending());dialog.value='';notice.value='服务器已确认操作成功';await load(data.page);});busy.value=false;}
async function confirm(text,fn){if(guestAction())return;if(blocked.value)return;const r=await new Promise(resolve=>uni.showModal({title:'确认操作',content:text,success:resolve}));if(r.confirm)await fn();}
const removeFavorite=row=>confirm('确认取消收藏？',()=>save('/favorites/'+row.id,undefined,'DELETE'));
const cancelSubscription=row=>confirm('确认取消这条有货提醒订阅？',()=>save('/stock-subscriptions/'+row.id,undefined,'DELETE'));
const markRead=row=>save('/notifications/'+row.id+'/read',{});
const toggle=row=>save((def.value.writePath||def.value.path)+'/'+row.id+'/enabled',{enabled:!row.enabled},'PATCH');
const removeLab=row=>props.user.role==='admin'&&confirm('确认删除“'+row.name+'”？历史预约会保留；存在尚未结束的待审批或已批准预约时不能删除。',()=>save('/labs/'+row.id,{version:row.version},'DELETE'));
const removeAnnouncement=row=>confirm('确认删除这条通知？',()=>save('/announcements/'+row.id,undefined,'DELETE'));
function availability(row){availabilityLab.value=row.id;title.value=row.name+' · 开放与余量';dialog.value='availability';}
async function assets(row){assetEquipment.value=row.id;title.value=row.name+' · 资产台账';await assetPage(1);if(!error.value)dialog.value='assets';}
async function assetPage(page){await safely(async()=>Object.assign(assetData,(await api.request('/equipment/'+assetEquipment.value+'/assets?page='+page+'&pageSize=10')).data));}
async function upload(key){if(guestAction())return;if(blocked.value)return;busy.value=true;await safely(async()=>{const file=await chooseAndUpload();if(file)form[key]=file.id;});busy.value=false;}
async function downloadResource(row){if(guestAction())return;busy.value=true;await safely(async()=>{const result=(await api.request('/resources/'+row.id+'/download')).data;await downloadFile(result.url,result.name);});busy.value=false;}
async function downloadAttachment(row){if(guestAction())return;busy.value=true;await safely(()=>downloadFile('/api/files/'+row.fileId+'/content',row.name+'附件'));busy.value=false;}
function close(){if(busy.value)return;dialog.value='';error.value='';}
const importText=ref(''),importValid=ref(false),importReport=ref(null),importPage=ref(1),importYearPlanId=ref(''),importConfirmedRows=ref([]),templateDownloading=ref(false);let importRevision=0,templateRevision=0,templateTask;
function clearImport(){importRevision++;clearTimeout(importPollTimer);importValid.value=false;importReport.value=null;importPage.value=1;importYearPlanId.value='';importConfirmedRows.value=[];}
watch(importText,clearImport);
async function downloadImportTemplate(){if(guestAction()||blocked.value||templateDownloading.value)return;const turn=++templateRevision,userId=props.user?.id;templateDownloading.value=true;error.value='';try{const apiBase=import.meta.env.VITE_API_BASE_URL||'/api',origin=apiBase.replace(/\/api\/?$/,'');const saved=await downloadAccountTemplate({origin:origin==='/'?'':origin,isCurrent:()=>alive&&turn===templateRevision&&!isGuest.value&&props.user?.id===userId&&props.user?.role==='admin'&&props.verified,onTask:task=>templateTask=task});if(saved&&alive&&turn===templateRevision)notice.value='账号导入模版已保存。';}catch(e){if(alive&&turn===templateRevision)error.value=e.message||'模版暂时无法下载';}finally{if(alive&&turn===templateRevision){templateDownloading.value=false;templateTask=null;}}}
onUnmounted(()=>{templateRevision++;templateTask?.abort();});
function setImportYearConfirmation(row,checked){if(blocked.value||!importYearPlanId.value)return;importConfirmedRows.value=checked?[...new Set([...importConfirmedRows.value,row.row])]:importConfirmedRows.value.filter(id=>id!==row.row);importValid.value=false;notice.value='已选择采用的表格年份，请重新校验，由服务器确认后才可导入。';}
const importReportLabel=computed(()=>{const report=importReport.value;if(!report)return '';return report.summary?'共 '+report.summary.total+' 行，可导入 '+report.summary.valid+' 人，年份待核实 '+(report.summary.needsConfirmation||0)+' 人':'成功 '+report.created+' 人，跳过 '+report.skipped+' 人，失败 '+report.failed+' 人';});
const importIssueRows=computed(()=>importReport.value?.rows?.filter(row=>['invalid','duplicate','ignored','confirmation'].includes(row.status))||[]);
const visibleImportErrors=computed(()=>importIssueRows.value.slice((importPage.value-1)*10,importPage.value*10));
async function pollImport(id,revision=importRevision){if(!alive||revision!==importRevision)return;let scheduled=false;await safely(async()=>{const job=(await api.request('/users/import/jobs/'+id)).data;if(!alive||revision!==importRevision)return;importReport.value=job;if(job.state==='running'){scheduled=true;importPollTimer=setTimeout(()=>pollImport(id,revision),600);}else{busy.value=false;notice.value='导入任务完成，已有账号保持原资料。';}});if(alive&&revision===importRevision&&!scheduled)busy.value=false;}
async function importUsers(preview){if(guestAction())return;if(blocked.value||(!preview&&!importValid.value))return;const revision=importRevision,format='unified-v2';busy.value=true;await safely(async()=>{if(!preview){const planId=importReport.value.planId;importValid.value=false;const job=(await api.request('/users/import/commit',{method:'POST',data:{planId}})).data;if(!alive||revision!==importRevision)return;importReport.value=job;await pollImport(job.id,revision);return;}const rows=parseImportText(importText.value,'mixed',format).map(row=>({...row,...(importConfirmedRows.value.includes(row.row)?{confirmEnrollmentYear:true}:{})}));const body={accountType:'mixed',format,rows,...(importConfirmedRows.value.length&&importYearPlanId.value?{confirmYearPlanId:importYearPlanId.value}:{})};const report=(await api.request('/users/import/preview',{method:'POST',data:body})).data;if(!alive||revision!==importRevision)return;importReport.value=report;importYearPlanId.value=report.planId;importValid.value=report.canCommit&&!missingRequiredRows(rows,format).length;if(missingRequiredRows(rows,format).length)notice.value='第'+missingRequiredRows(rows,format).join('、')+'行缺账号或姓名，请补齐后重新校验。';importPage.value=1;});if(alive&&revision===importRevision&&importReport.value?.state!=='running')busy.value=false;}
const periods=[{id:'year',name:'本年度'},{id:'semester',name:'本学期'},{id:'custom',name:'自选日期'}],period=ref('year'),from=ref(''),to=ref(''),stats=reactive({});
const statisticNames={borrowers:'实际借用人数',issued:'实际借出件数',returned:'完好归还件数',added:'入库件数',removed:'移出件数',damaged:'损坏件数',lost:'丢失件数',retired:'报废件数',discrepancy:'盘亏件数',repair:'当前待维修'};
async function loadStats(page=1){loading.value=true;await safely(async()=>{const ranges=query({period:period.value,...(period.value==='custom'?{from:from.value,to:to.value}:{})});const [summary,heat]=await Promise.all([api.request('/statistics?'+ranges),api.request('/statistics/equipment-heat?'+ranges+'&page='+page+'&pageSize=10')]);Object.assign(stats,summary.data);Object.assign(data,heat.data);});loading.value=false;}
defineExpose({reload:()=>props.module==='statistics'?loadStats(data.page):load(data.page)});
onMounted(async()=>{await safely(async()=>{const all=[];let page=1,total;do{const r=await api.request('/equipment?pageSize=100&page='+page);all.push(...r.data.items);total=r.data.total;page++;}while(all.length<total&&page<=1000);equipment.value=all;});if(props.module==='resources'){await safely(async()=>{const result=(await api.request('/resources/categories')).data;resourceCategories.value=result.items;resourceWriteCategories.value=result.writableItems||[];});}else if(props.module==='works'){await safely(async()=>{const categories=new Set();let page=1,total=0,seen=0;do{const result=(await api.request(def.value.path+'?pageSize=100&page='+page)).data;for(const row of result.items)if(row.category)categories.add(row.category);seen+=result.items.length;total=result.total;page++;}while(seen<total&&page<=100);resourceCategories.value=[...categories];});}if(props.module==='statistics')await loadStats();else await load();if(['statistics','importUsers'].includes(props.module))dataReady.value=true;});
</script>
<style scoped>
@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
@import '../pages/index/college-refinement.css';
.equipment-photo{display:flex;flex-direction:column;align-items:flex-start;gap:12px}.photo-preview{width:100%;height:180px;background:#f3f7fa;border:1px solid #d7e3ed;border-radius:8px}
.module-row{padding:20px 0;border-bottom:1px solid #e1e8ef}.module-row:first-child{padding-top:0}.module-row:last-child{border-bottom:0}.module-values{display:flex;flex-direction:column;gap:7px;font-size:14px;line-height:1.8;color:#35556d}.value-label{color:#60778a}.module-actions{margin-bottom:0}.module-panel{min-width:0}.module-values text{overflow-wrap:anywhere}.member-card{display:flex;flex-direction:column;gap:10px;border:1px solid #d7e3ed;padding:14px;border-radius:8px;margin:12px 0}.import-area{height:240px;margin-top:20px}.module-panel .modal-mask{z-index:120}
.compact-filter{max-width:470px;margin:16px 0}.resource-filters{margin-bottom:22px}.author-summary{display:flex;flex-direction:column;gap:10px;padding:20px;background:#edf3f8;border-radius:12px;margin:12px 0 24px}.work-media image,.work-media video{width:100%;max-height:520px;border-radius:12px}.work-media{margin-bottom:24px}.full-content-panel{left:230px;top:80px;bottom:0;right:0;align-items:stretch;padding:0;background:#f5f6f7;backdrop-filter:none}.full-content-panel .modal{width:100%;max-width:1100px;margin:0 auto;border-radius:0;box-shadow:none;background:#f5f6f7}.full-content-panel .modal-header{padding:24px 32px;background:white}.full-content-panel .modal-scroll{height:calc(100vh - 158px);max-height:none}.full-content-panel .modal-content{padding:28px 32px;background:white;margin:24px;border-radius:14px}.module-panel .value-label,.module-panel .module-values{color:#111}.module-panel .record-id{display:none}@media(max-width:1200px){.full-content-panel{left:200px}}@media(max-width:760px){.full-content-panel{left:0;top:63px;bottom:70px;z-index:80}.full-content-panel .modal-content{padding:20px;margin:14px}.full-content-panel .modal-scroll{height:calc(100vh - 205px)}.full-content-panel .modal-header{padding:18px 20px}}
.content-page{width:100%;min-width:0}.content-document{background:#fff;border:1px solid #d8e2ea;border-radius:14px;overflow:hidden}.content-document .modal-header{padding:24px 30px;background:#fff}.content-document .modal-content{padding:28px 30px}.content-page-body{height:auto;max-height:none;overflow:visible}.page-back{margin-bottom:18px!important}.content-document .block-title{font-size:23px}.record-document{max-width:1050px;margin:0 auto}.record-document .detail-title{font-size:26px}@media(max-width:760px){.content-document .modal-header,.content-document .modal-content{padding:20px}.content-document .block-title{font-size:21px}}
@media(max-width:760px){.module-row{padding:14px 0}.module-values{gap:5px}.module-actions{gap:7px;margin-top:10px}.member-card{padding:12px;gap:8px;margin:10px 0}.content-document .modal-header,.content-document .modal-content{padding:16px}.content-document .block-title{font-size:19px}.author-summary{padding:14px;gap:7px;margin:10px 0 16px}.resource-filters{margin-bottom:16px}.compact-filter{margin:12px 0}.page-back{margin-bottom:12px!important}}

.work-document-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:24px}.work-document-heading .detail-title{margin:0}.work-section{padding:24px 0;border-top:1px solid #dce4ec;display:flex;flex-direction:column;gap:14px}.work-section .block-title{font-size:17px}.work-prose{display:block;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;color:#111}.work-section .btn,.work-section .text-btn{align-self:flex-start}.work-section .work-media{margin:0}.work-section .muted{line-height:1.8}
@media(max-width:760px){.work-section{padding:18px 0;gap:10px}.work-document-heading{margin-bottom:18px}.work-document-heading .detail-title{font-size:22px}}

.booking-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.booking-form-grid>.muted,.booking-form-grid>.author-summary,.booking-form-grid>.form-field-wide,.booking-form-grid>.btn{grid-column:1/-1}.booking-form-grid .field-group{min-width:0}.booking-form-grid>.btn{margin-top:12px}
@media(max-width:760px){.booking-form-grid{grid-template-columns:minmax(0,1fr);gap:0}.booking-form-grid .field-group{grid-column:1}}
.lab-period-options checkbox-group{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 14px}.lab-period-options .check-row{margin:8px 0}.lab-opening-plan{border:1px solid #d7e2ef;padding:16px;border-radius:9px;background:#f8fafc}.lab-opening-plan>.check-row{margin-top:0}.lab-opening-plan .small{display:block;margin-bottom:7px}.module-panel .btn:not(.secondary):not([disabled]){color:#fff!important}.module-panel .btn.danger{color:#a43229}.student-import-slot{min-width:0}@media(max-width:760px){.lab-period-options checkbox-group{grid-template-columns:1fr}.lab-opening-plan{padding:12px}}

.work-privacy-options{display:flex;flex-wrap:wrap;gap:12px}
.work-privacy-option{display:flex;align-items:center;gap:8px;min-height:44px;padding:8px 14px;border:1px solid var(--lab-line,#dce3ef);border-radius:8px;cursor:pointer}
.work-privacy-option.selected{border-color:var(--lab-blue,#4264eb);background:#fff}
.work-privacy-options .privacy-hint{display:block;width:100%;line-height:1.7}
</style>

<style scoped>.booking-time{display:block}</style>
