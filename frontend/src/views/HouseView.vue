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
  for (const h of houses.value) {
    penCount += h.pens.length
    pigCount += h.pigCount || 0
    capacity += h.capacity || 0
  }
  return { penCount, pigCount, capacity, usage: capacity ? Math.round((pigCount * 100) / capacity) : 0 }
})

async function load () {
  houses.value = await api.house.grid()
}

function typeLabel (type) {
  const hit = HOUSE_TYPES.find((t) => t.value === type)
  return hit ? hit.label : type
}

async function openPen (pen) {
  activePenId.value = pen.id
  penDetail.value = pen
  penPigs.value = await api.house.penPigs(pen.id)
  drawer.value = true
}

async function toggleHouse (house) {
  const next = house.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
  await api.house.status(house.id, next)
  ElMessage.success(next === 'DISABLED' ? '栋舍已停用，其下栏位不允许转入猪只' : '栋舍已启用')
  load()
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
      <span class="view-tip">平面格子图：一格一栏位，点格子看存栏</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: typeFilter === '' }" @click="typeFilter = ''">全部栋舍</span>
      <span v-for="t in HOUSE_TYPES" :key="t.value" class="chip" :class="{ on: typeFilter === t.value }" @click="typeFilter = t.value">{{ t.label }}</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="houseDialog = true">新建栋舍</el-button>
    </div>

    <div class="grid-3" style="margin-bottom: 12px">
      <div class="mini-card"><div class="k">栋舍 / 栏位</div><div class="v">{{ houses.length }} / {{ summary.penCount }}</div></div>
      <div class="mini-card"><div class="k">在栏猪只 / 总容量</div><div class="v">{{ summary.pigCount }} / {{ summary.capacity }}</div></div>
      <div class="mini-card"><div class="k">全场存栏率</div><div class="v">{{ summary.usage }}%</div></div>
    </div>

    <div class="grid-2" style="grid-template-columns: repeat(auto-fill, minmax(420px, 1fr))">
      <div v-for="house in filtered" :key="house.id" class="house-card" :class="{ disabled: house.disabled }">
        <div class="house-head">
          <span class="house-code">{{ house.houseCode }}</span>
          <span class="tag solid" :style="{ background: house.houseTypeColor }">{{ house.houseTypeName }}</span>
          <span class="tag">{{ house.statusName }}</span>
          <el-button size="small" link @click="openPenDialog(house)">+ 栏位</el-button>
          <el-button size="small" link :type="house.disabled ? 'success' : 'danger'" @click="toggleHouse(house)">
            {{ house.disabled ? '启用' : '停用' }}
          </el-button>
        </div>
        <div class="house-meta">
          {{ house.houseName }} · 饲养员 {{ house.keeper || '—' }} · {{ house.areaSqm || 0 }}㎡ ·
          {{ house.penCount }} 栏 · 存栏 {{ house.pigCount }}/{{ house.capacity }}
        </div>

        <div class="pen-grid">
          <div
            v-for="pen in house.pens"
            :key="pen.id"
            class="pen-cell"
            :class="{ on: activePenId === pen.id, blocked: house.disabled || pen.status === 'REPAIR' || pen.status === 'CLEANING' }"
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
