<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const HOUSE_TYPES = [
  { value: 'GESTATION', label: '配怀' },
  { value: 'FARROWING', label: '分娩' },
  { value: 'NURSERY', label: '保育' },
  { value: 'FINISHING', label: '育肥' },
  { value: 'ISOLATION', label: '隔离' }
]

// 状态链路：检修立即生效原地照护；停用先腾空（待清空）再生效；恢复在用重新评估
const STATUS_ACTIONS = {
  ACTIVE: [
    { target: 'MAINTAIN', label: '检修', type: 'warning' },
    { target: 'DISABLED', label: '停用', type: 'danger' }
  ],
  MAINTAIN: [
    { target: 'ACTIVE', label: '恢复在用', type: 'success' },
    { target: 'DISABLED', label: '停用', type: 'danger' }
  ],
  DRAINING: [
    { target: 'DISABLED', label: '确认停用', type: 'danger' },
    { target: 'ACTIVE', label: '取消停用', type: 'success' }
  ],
  DISABLED: [
    { target: 'ACTIVE', label: '恢复在用', type: 'success' }
  ]
}

const houses = ref([])
const typeFilter = ref('')
const activePenId = ref(null)
const drawer = ref(false)
const penDetail = ref(null)
const penPigs = ref([])

const houseDialog = ref(false)
const penDialog = ref(false)
const houseForm = ref(emptyHouse())
const penForm = ref(emptyPen())
const editPen = ref(false)

// 状态切换确认框：预览 → 填写原因 → 带幂等键确认
const statusDialog = ref(false)
const statusHouse = ref(null)
const statusTarget = ref('')
const statusPreview = ref(null)
const statusReason = ref('')
const statusOperator = ref('场长')
const statusRequestNo = ref('')
const statusSubmitting = ref(false)

const anomalyOpen = ref({})

function emptyHouse () {
  return { houseCode: '', houseName: '', houseType: 'FINISHING', areaSqm: null, keeper: '', status: 'ACTIVE', note: '' }
}
function emptyPen () {
  return { houseId: null, penCode: '', penName: '', capacity: 10, status: 'NORMAL', note: '' }
}

const filtered = computed(() => {
  if (!typeFilter.value) return houses.value
  return houses.value.filter((h) => h.houseType === typeFilter.value)
})

const summary = computed(() => {
  let penCount = 0
  let pigCount = 0
  let capacity = 0
  let restricted = 0
  let anomalies = 0
  for (const h of houses.value) {
    penCount += h.pens.length
    pigCount += h.pigCount || 0
    capacity += h.capacity || 0
    if (h.entryFrozen) restricted += 1
    anomalies += (h.anomalies || []).length
  }
  return { penCount, pigCount, capacity, restricted, anomalies, usage: capacity ? Math.round((pigCount * 100) / capacity) : 0 }
})

const activeHouse = computed(() => houses.value.find((h) => h.id === penDetail.value?.houseId) || null)

const dialogTitle = computed(() => {
  if (!statusHouse.value) return ''
  const names = { MAINTAIN: '申请检修', DISABLED: '申请停用', ACTIVE: '恢复在用' }
  return `${statusHouse.value.houseCode} ${names[statusTarget.value] || ''}`
})

const confirmLabel = computed(() => {
  const become = statusPreview.value?.willBecome
  return { MAINTAIN: '确认检修（立即生效）', DISABLED: '确认停用', DRAINING: '进入待清空', ACTIVE: '确认恢复在用' }[become] || '确认'
})

const reasonPlaceholder = computed(() => ({
  MAINTAIN: '如：产床加热线检修，预计 3 天',
  DISABLED: '如：全场停用改造',
  ACTIVE: '如：检修完成，评估后恢复'
}[statusTarget.value] || '填写本次状态切换原因'))

async function load () {
  houses.value = await api.house.grid()
}

function typeLabel (type) {
  const hit = HOUSE_TYPES.find((t) => t.value === type)
  return hit ? hit.label : type
}

function actionsFor (house) {
  return STATUS_ACTIONS[house.status] || []
}

function fmtTime (value) {
  return value ? String(value).slice(0, 16).replace('T', ' ') : ''
}

function drainPercent (house) {
  const total = (house.pens || []).length
  if (!total) return 100
  const cleared = house.pens.filter((p) => (p.pigCount || 0) === 0).length
  return Math.round((cleared * 100) / total)
}

function toggleAnomaly (id) {
  anomalyOpen.value[id] = !anomalyOpen.value[id]
}

async function openPen (pen) {
  activePenId.value = pen.id
  penDetail.value = pen
  penPigs.value = await api.house.penPigs(pen.id)
  drawer.value = true
}

function makeRequestNo () {
  return 'HST-' + Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 8).toUpperCase()
}

// 操作员确认前先预览：受影响栏位、猪只清单、阻塞原因都在确认框里
async function openStatus (house, target) {
  statusHouse.value = house
  statusTarget.value = target
  statusReason.value = ''
  statusRequestNo.value = makeRequestNo()
  statusSubmitting.value = false
  try {
    statusPreview.value = await api.house.statusPreview(house.id, target)
    statusDialog.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function confirmStatus () {
  if (statusSubmitting.value) return
  statusSubmitting.value = true
  try {
    const result = await api.house.status(statusHouse.value.id, {
      target: statusTarget.value,
      reason: statusReason.value,
      operator: statusOperator.value,
      requestNo: statusRequestNo.value
    })
    // 幂等重放 / 未腾空停留待清空 都以后端结论为准
    ElMessage[result.noop ? 'info' : 'success'](result.message)
    statusDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    statusSubmitting.value = false
  }
}

async function submitHouse () {
  try {
    await api.house.create(houseForm.value)
    ElMessage.success('栋舍已创建')
    houseDialog.value = false
    houseForm.value = emptyHouse()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openPenDialog (house) {
  editPen.value = false
  penForm.value = emptyPen()
  penForm.value.houseId = house.id
  penForm.value.capacity = defaultCapacity(house.houseType)
  penDialog.value = true
}

function defaultCapacity (type) {
  return { GESTATION: 6, FARROWING: 1, NURSERY: 20, FINISHING: 30, ISOLATION: 8 }[type] || 10
}

async function submitPen () {
  try {
    if (editPen.value) {
      await api.house.updatePen(penForm.value.id, penForm.value)
      ElMessage.success('栏位已更新')
    } else {
      await api.house.createPen(penForm.value)
      ElMessage.success('栏位已创建')
    }
    penDialog.value = false
    drawer.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function editThisPen () {
  editPen.value = true
  penForm.value = { ...penDetail.value, capacity: penDetail.value.capacity }
  penDialog.value = true
}

async function removeThisPen () {
  await ElMessageBox.confirm('确认删除该栏位（栏内不能有猪只）？', '删除栏位', { type: 'warning' })
  await api.house.removePen(penDetail.value.id)
  ElMessage.success('栏位已删除')
  drawer.value = false
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">栋舍与栏位</span>
      <span class="view-tip">检修立即生效原地照护 · 停用先腾空再生效 · 平面格子图点格看存栏</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: typeFilter === '' }" @click="typeFilter = ''">全部栋舍</span>
      <span v-for="t in HOUSE_TYPES" :key="t.value" class="chip" :class="{ on: typeFilter === t.value }" @click="typeFilter = t.value">{{ t.label }}</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="houseDialog = true">新建栋舍</el-button>
    </div>

    <div class="grid-3" style="grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 12px">
      <div class="mini-card"><div class="k">栋舍 / 栏位</div><div class="v">{{ houses.length }} / {{ summary.penCount }}</div></div>
      <div class="mini-card"><div class="k">在栏猪只 / 总容量</div><div class="v">{{ summary.pigCount }} / {{ summary.capacity }}</div></div>
      <div class="mini-card"><div class="k">受限栋舍（冻结新增）</div><div class="v">{{ summary.restricted }}</div></div>
      <div class="mini-card"><div class="k">待处理异常</div><div class="v">{{ summary.anomalies }}</div></div>
    </div>

    <div class="grid-2" style="grid-template-columns: repeat(auto-fill, minmax(420px, 1fr))">
      <div v-for="house in filtered" :key="house.id" class="house-card" :class="{ restricted: house.entryFrozen }">
        <div class="house-head">
          <span class="house-code">{{ house.houseCode }}</span>
          <span class="tag solid" :style="{ background: house.houseTypeColor }">{{ house.houseTypeName }}</span>
          <span class="tag st" :class="'st-' + house.status">{{ house.statusName }}</span>
          <span v-if="house.entryFrozen" class="tag frozen">冻结新增</span>
          <el-button size="small" link @click="openPenDialog(house)">+ 栏位</el-button>
          <el-button
            v-for="a in actionsFor(house)"
            :key="a.target"
            size="small"
            link
            :type="a.type"
            @click="openStatus(house, a.target)"
          >{{ a.label }}</el-button>
        </div>
        <div class="house-meta">
          {{ house.houseName }} · 饲养员 {{ house.keeper || '—' }} · {{ house.areaSqm || 0 }}㎡ ·
          {{ house.penCount }} 栏 · 存栏 {{ house.pigCount }}/{{ house.capacity }}
        </div>

        <!-- 生效中的受限原因 -->
        <div v-if="house.restrictionReason" class="house-restrict" :class="'st-' + house.status">
          {{ house.restrictionReason }}
          <span v-if="house.statusChangedAt" class="hr-time">生效于 {{ fmtTime(house.statusChangedAt) }}</span>
        </div>

        <!-- 待清空：存栏处置进度 + 阻塞原因 -->
        <div v-if="house.status === 'DRAINING'" class="drain-progress">
          <div class="dp-text">
            存栏处置进度：已清空 {{ house.pens.filter((p) => !p.pigCount).length }}/{{ house.pens.length }} 栏 ·
            待迁出 {{ house.remainingPigs }} 头 · 未完操作栏位 {{ house.unfinishedPens }} 个
          </div>
          <div class="load-bar" :class="{ full: house.remainingPigs > 0 || house.unfinishedPens > 0 }">
            <i :style="{ width: drainPercent(house) + '%' }"></i>
          </div>
          <div v-for="(b, i) in house.blockers" :key="i" class="dp-blocker">· {{ b }}</div>
        </div>

        <!-- 待处理异常：恢复在用也不会被静默清除 -->
        <div v-if="house.anomalies && house.anomalies.length" class="house-anomaly">
          <span class="ha-toggle" @click="toggleAnomaly(house.id)">
            待处理异常 {{ house.anomalies.length }} 项 {{ anomalyOpen[house.id] ? '▲ 收起' : '▼ 展开' }}
          </span>
          <div v-if="anomalyOpen[house.id]">
            <div v-for="(a, i) in house.anomalies" :key="i" class="ha-line">[{{ a.typeName }}] {{ a.detail }}</div>
          </div>
        </div>

        <div class="pen-grid">
          <div
            v-for="pen in house.pens"
            :key="pen.id"
            class="pen-cell"
            :class="{ on: activePenId === pen.id, blocked: house.entryFrozen || pen.status === 'REPAIR' || pen.status === 'CLEANING' }"
            @click="openPen(pen)"
          >
            <div class="pc-name">{{ pen.penName || pen.penCode }}</div>
            <div class="pc-count">{{ pen.pigCount }} <span class="pc-cap">/ {{ pen.capacity}} 头</span></div>
            <div class="load-bar" :class="{ full: pen.usageRate >= 1 }"><i :style="{ width: Math.round(pen.usageRate * 100) + '%' }"></i></div>
            <div class="pc-cap">{{ pen.statusName }} · 余 {{ pen.remaining }}</div>
          </div>
          <div v-if="!house.pens.length" class="empty-tip">该栋舍还没有栏位</div>
        </div>
      </div>
    </div>

    <!-- 点格子看存栏 -->
    <el-drawer v-model="drawer" :title="penDetail ? penDetail.penCode + ' 存栏明细' : ''" direction="rtl" size="430px">
      <div class="batch-drawer" v-if="penDetail">
        <div v-if="activeHouse && activeHouse.entryFrozen" class="drawer-tip">
          该栋舍「{{ activeHouse.statusName }}」生效中：冻结新增入栏 / 转入，栏内猪只仅可迁出或就地照护（饲喂、健康事件照常登记）
        </div>
        <div class="grid-2" style="grid-template-columns: 1fr 1fr; margin-bottom: 12px">
          <div class="mini-card"><div class="k">存栏 / 容量</div><div class="v">{{ penDetail.pigCount }} / {{ penDetail.capacity }}</div></div>
          <div class="mini-card"><div class="k">剩余位置</div><div class="v">{{ penDetail.remaining }}</div></div>
        </div>
        <div class="section-title">栏内猪只（{{ penPigs.length }}）</div>
        <div v-for="pig in penPigs" :key="pig.id" class="pig-row">
          <div>
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">{{ pig.breedName }} · {{ pig.genderName }} · {{ pig.ageInDays }} 日龄 · {{ pig.weightKg }}kg</div>
          </div>
          <span class="tag" style="margin-left: auto">{{ pig.statusName }}</span>
        </div>
        <div v-if="!penPigs.length" class="empty-tip">该栏位暂无猪只</div>
        <div style="margin-top: 12px; display: flex; gap: 8px">
          <el-button size="small" @click="editThisPen">编辑栏位</el-button>
          <el-button size="small" type="danger" plain @click="removeThisPen">删除栏位</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 状态切换确认：受影响栏位 + 猪只清单 + 阻塞原因，确认后才生效 -->
    <el-dialog v-model="statusDialog" :title="dialogTitle" width="580px">
      <div v-if="statusPreview" class="status-preview">
        <div class="sp-flow">
          <span class="tag st" :class="'st-' + statusPreview.currentStatus">{{ statusPreview.currentStatusName }}</span>
          <span class="sp-arrow">→</span>
          <span class="tag st" :class="'st-' + statusPreview.willBecome">{{ statusPreview.willBecomeName }}</span>
          <span v-if="statusPreview.entryFrozenAfter" class="tag frozen">冻结新增</span>
        </div>
        <div class="sp-decision">{{ statusPreview.decisionText }}</div>

        <div v-if="statusPreview.blockers.length" class="sp-block blocked">
          <div class="sp-title">阻塞原因（{{ statusPreview.blockers.length }}）</div>
          <div v-for="(b, i) in statusPreview.blockers" :key="i" class="sp-line">· {{ b }}</div>
        </div>

        <div v-if="statusPreview.anomalies.length" class="sp-block anomaly">
          <div class="sp-title">待处理异常（{{ statusPreview.anomalies.length }}）· 恢复后仍保留，不会被清除</div>
          <div v-for="(a, i) in statusPreview.anomalies" :key="i" class="sp-line">· [{{ a.typeName }}] {{ a.detail }}</div>
        </div>

        <div class="sp-title">受影响栏位（{{ statusPreview.pens.length }}）</div>
        <el-table :data="statusPreview.pens" size="small" max-height="180">
          <el-table-column prop="penCode" label="栏位" width="120" />
          <el-table-column prop="statusName" label="状态" width="90" />
          <el-table-column label="存栏 / 容量" width="100">
            <template #default="{ row }">{{ row.pigCount }} / {{ row.capacity }}</template>
          </el-table-column>
          <el-table-column label="未完操作">
            <template #default="{ row }">
              <span v-if="row.status === 'CLEANING' || row.status === 'REPAIR'" class="sp-warn">{{ row.statusName }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="sp-title">受影响猪只（{{ statusPreview.pigs.length }}）</div>
        <div v-if="statusPreview.pigs.length" class="sp-pigs">
          <span v-for="p in statusPreview.pigs" :key="p.id" class="tag">{{ p.earTag }}<template v-if="p.penCode"> · {{ p.penCode }}</template></span>
        </div>
        <div v-else class="empty-tip" style="padding: 8px">无在舍猪只</div>

        <el-form label-width="90px" style="margin-top: 12px">
          <el-form-item label="切换原因">
            <el-input v-model="statusReason" :placeholder="reasonPlaceholder" />
          </el-form-item>
          <el-form-item label="操作人">
            <el-input v-model="statusOperator" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="statusDialog = false">取消</el-button>
        <el-button type="primary" :loading="statusSubmitting" @click="confirmStatus">{{ confirmLabel }}</el-button>
      </template>
    </el-dialog>

    <!-- 新建栋舍 -->
    <el-dialog v-model="houseDialog" title="新建栋舍" width="420px">
      <el-form :model="houseForm" label-width="90px">
        <el-form-item label="栋舍编号"><el-input v-model="houseForm.houseCode" placeholder="如 HS-F01" /></el-form-item>
        <el-form-item label="栋舍名称"><el-input v-model="houseForm.houseName" placeholder="如 三号育肥舍" /></el-form-item>
        <el-form-item label="栋舍类型">
          <el-select v-model="houseForm.houseType" style="width: 100%">
            <el-option v-for="t in HOUSE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="建筑面积"><el-input-number v-model="houseForm.areaSqm" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="饲养员"><el-input v-model="houseForm.keeper" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="houseForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="houseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitHouse">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建 / 编辑栏位 -->
    <el-dialog v-model="penDialog" :title="editPen ? '编辑栏位' : '新建栏位'" width="420px">
      <el-form :model="penForm" label-width="90px">
        <el-form-item label="所属栋舍">
          <el-select v-model="penForm.houseId" style="width: 100%">
            <el-option v-for="h in houses" :key="h.id" :label="h.houseCode + ' ' + h.houseName" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="栏位编号"><el-input v-model="penForm.penCode" placeholder="如 HS-F01-03" /></el-form-item>
        <el-form-item label="栏位名称"><el-input v-model="penForm.penName" /></el-form-item>
        <el-form-item label="容量"><el-input-number v-model="penForm.capacity" :min="1" :max="200" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="penForm.status" style="width: 100%">
            <el-option label="正常" value="NORMAL" />
            <el-option label="满栏" value="FULL" />
            <el-option label="清洗消毒" value="CLEANING" />
            <el-option label="维修" value="REPAIR" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="penDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPen">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
