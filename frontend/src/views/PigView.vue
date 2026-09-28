<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const BREEDS = [
  { value: 'YORKSHIRE', label: '大白' },
  { value: 'LANDRACE', label: '长白' },
  { value: 'DUROC', label: '杜洛克' },
  { value: 'PIETRAIN', label: '皮特兰' },
  { value: 'BERKSHIRE', label: '巴克夏' }
]
const STATUSES = [
  { value: 'ON_FARM', label: '在场' },
  { value: 'TRANSFERRED', label: '转栏' },
  { value: 'SOLD', label: '出栏' },
  { value: 'DEAD', label: '死亡' },
  { value: 'CULLED', label: '淘汰' }
]

const pigs = ref([])
const pens = ref([])
const statusFilter = ref('')
const keyword = ref('')
const selected = ref([])
const batchOpen = ref(false)
const dialog = ref(false)
const form = ref(emptyForm())
const editingId = ref(null)
const batchStatus = ref('SOLD')
const batchPenId = ref(null)

function emptyForm () {
  return {
    earTag: '', breed: 'YORKSHIRE', gender: 'FEMALE', birthDate: '', sourceType: 'SELF',
    penId: null, weightKg: null, note: ''
  }
}

const filtered = computed(() => pigs.value.filter((p) => {
  if (statusFilter.value && p.status !== statusFilter.value) return false
  if (keyword.value && !p.earTag.includes(keyword.value.trim())) return false
  return true
}))

const selectedPigs = computed(() => pigs.value.filter((p) => selected.value.includes(p.id)))

async function load () {
  pigs.value = await api.pig.list()
  pens.value = await api.house.pens()
}

function toggle (pig) {
  const idx = selected.value.indexOf(pig.id)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
  } else {
    selected.value.push(pig.id)
    if (!batchOpen.value) batchOpen.value = true
  }
}

function closeBatch () {
  batchOpen.value = false
  selected.value = []
}

async function runBatchStatus () {
  try {
    const n = await api.pig.batchStatus(selected.value, batchStatus.value)
    ElMessage.success(`批量${statusName(batchStatus.value)}完成，共 ${n} 头`)
    closeBatch()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function runBatchTransfer () {
  if (!batchPenId.value) {
    ElMessage.warning('请选择目标栏位')
    return
  }
  try {
    const list = await api.transfer.create({ pigIds: selected.value, toPenId: batchPenId.value, reason: 'DENSITY' })
    ElMessage.success(`批量转栏完成，共 ${list.length} 头`)
    closeBatch()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function changeOne (pig, status) {
  try {
    await api.pig.status(pig.id, status)
    ElMessage.success(`${pig.earTag} 已${statusName(status)}`)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function statusName (v) {
  const hit = STATUSES.find((s) => s.value === v)
  return hit ? hit.label : v
}

function openCreate () {
  editingId.value = null
  form.value = emptyForm()
  dialog.value = true
}

function openEdit (pig) {
  editingId.value = pig.id
  form.value = {
    earTag: pig.earTag, breed: pig.breed, gender: pig.gender, birthDate: pig.birthDate,
    sourceType: pig.sourceType, penId: pig.penId, weightKg: pig.weightKg, note: pig.note
  }
  dialog.value = true
}

async function submit () {
  try {
    if (editingId.value) {
      await api.pig.update(editingId.value, form.value)
      ElMessage.success('猪只已更新')
    } else {
      await api.pig.create(form.value)
      ElMessage.success('猪只已入栏')
    }
    dialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePig (pig) {
  await ElMessageBox.confirm(`确认删除 ${pig.earTag}？`, '删除猪只', { type: 'warning' })
  await api.pig.remove(pig.id)
  ElMessage.success('猪只已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">猪只个体与耳标</span>
      <span class="view-tip">耳标列表 + 侧滑批量操作（勾选后右侧滑出批量面板）</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: statusFilter === '' }" @click="statusFilter = ''">全部</span>
      <span v-for="s in STATUSES" :key="s.value" class="chip" :class="{ on: statusFilter === s.value }" @click="statusFilter = s.value">{{ s.label }}</span>
      <el-input v-model="keyword" size="small" placeholder="按耳标号搜索" style="width: 180px; margin-left: 10px" />
      <el-button size="small" type="primary" style="margin-left: auto" @click="openCreate">新建猪只</el-button>
    </div>

    <div class="grid-2" style="grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr)">
      <div class="panel">
        <div class="section-title">耳标台账（{{ filtered.length }}）</div>
        <div v-for="pig in filtered" :key="pig.id" class="pig-row" :class="{ on: selected.includes(pig.id) }">
          <el-checkbox :model-value="selected.includes(pig.id)" @click.stop="toggle(pig)" />
          <div style="flex: 1 1 auto; min-width: 0" @click="toggle(pig)">
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">
              {{ pig.breedName }} · {{ pig.genderName }} · {{ pig.ageInDays }} 日龄 ·
              {{ pig.weightKg }}kg · {{ pig.penCode || '无栏位' }} · {{ pig.batchNo || '未入批' }}
            </div>
          </div>
          <span class="tag" :style="{ background: pig.breedColor, color: '#fff' }">{{ pig.breedName }}</span>
          <span class="tag">{{ pig.statusName }}</span>
          <el-dropdown trigger="click" @command="(c) => c === 'edit' ? openEdit(pig) : c === 'del' ? removePig(pig) : changeOne(pig, c)">
            <el-button size="small" link>操作</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="TRANSFERRED">标记转栏</el-dropdown-item>
                <el-dropdown-item command="SOLD">标记出栏</el-dropdown-item>
                <el-dropdown-item command="DEAD">标记死亡</el-dropdown-item>
                <el-dropdown-item command="CULLED">标记淘汰</el-dropdown-item>
                <el-dropdown-item command="edit" divided>编辑</el-dropdown-item>
                <el-dropdown-item command="del">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div v-if="!filtered.length" class="empty-tip">没有符合条件的猪只</div>
      </div>

      <div class="panel">
        <div class="section-title">品种 / 状态速览</div>
        <div class="mini-card" style="margin-bottom: 10px">
          <div class="k">在栏猪只</div>
          <div class="v">{{ pigs.filter((p) => p.status === 'ON_FARM' || p.status === 'TRANSFERRED').length }}</div>
        </div>
        <div v-for="b in BREEDS" :key="b.value" style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
          <span class="tag solid" :style="{ background: { YORKSHIRE: '#ffb74d', LANDRACE: '#4fc3f7', DUROC: '#e57373', PIETRAIN: '#81c784', BERKSHIRE: '#ba68c8' }[b.value] }">{{ b.label }}</span>
          <div class="load-bar" style="flex: 1 1 auto; margin: 0">
            <i :style="{ width: Math.round((pigs.filter((p) => p.breed === b.value).length * 100) / (pigs.length || 1)) + '%' }"></i>
          </div>
          <b style="font-size: 12px">{{ pigs.filter((p) => p.breed === b.value).length }}</b>
        </div>
      </div>
    </div>

    <!-- 侧滑批量操作 -->
    <el-drawer v-model="batchOpen" title="批量操作" direction="rtl" size="380px" @close="selected = []">
      <div class="batch-drawer">
        <div class="mini-card" style="margin-bottom: 12px">
          <div class="k">已勾选猪只</div>
          <div class="v">{{ selected.length }} 头</div>
        </div>
        <div class="pig-row" v-for="pig in selectedPigs" :key="pig.id">
          <div>
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">{{ pig.breedName }} · {{ pig.statusName }} · {{ pig.penCode || '无栏位' }}</div>
          </div>
        </div>
        <div v-if="!selectedPigs.length" class="empty-tip">还没有勾选猪只</div>

        <div class="section-title" style="margin-top: 14px">批量推进状态</div>
        <el-select v-model="batchStatus" size="small" style="width: 100%; margin-bottom: 8px">
          <el-option label="标记转栏" value="TRANSFERRED" />
          <el-option label="标记出栏" value="SOLD" />
          <el-option label="标记死亡" value="DEAD" />
          <el-option label="标记淘汰" value="CULLED" />
        </el-select>
        <el-button type="primary" size="small" style="width: 100%" @click="runBatchStatus">执行批量状态变更</el-button>

        <div class="section-title" style="margin-top: 16px">批量转栏</div>
        <el-select v-model="batchPenId" size="small" placeholder="选择目标栏位" style="width: 100%; margin-bottom: 8px">
          <el-option v-for="pen in pens" :key="pen.id" :label="pen.penCode + '（余 ' + Math.max(0, (pen.capacity || 0) - (pen.pigCount || 0)) + '）'" :value="pen.id" />
        </el-select>
        <el-button size="small" style="width: 100%" @click="runBatchTransfer">批量搬移到该栏位</el-button>
        <el-button size="small" style="width: 100%; margin-top: 8px" @click="closeBatch">清空选择</el-button>
      </div>
    </el-drawer>

    <!-- 新建 / 编辑猪只 -->
    <el-dialog v-model="dialog" :title="editingId ? '编辑猪只' : '新建猪只'" width="440px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="耳标号"><el-input v-model="form.earTag" placeholder="如 ET-2609-0101" /></el-form-item>
        <el-form-item label="品种">
          <el-select v-model="form.breed" style="width: 100%">
            <el-option v-for="b in BREEDS" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.gender" style="width: 100%">
            <el-option label="母" value="FEMALE" />
            <el-option label="公" value="MALE" />
          </el-select>
        </el-form-item>
        <el-form-item label="出生日期">
          <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="不能晚于今天" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.sourceType" style="width: 100%">
            <el-option label="自繁" value="SELF" />
            <el-option label="外购" value="PURCHASE" />
          </el-select>
        </el-form-item>
        <el-form-item label="所在栏位">
          <el-select v-model="form.penId" clearable style="width: 100%">
            <el-option v-for="pen in pens" :key="pen.id" :label="pen.penCode" :value="pen.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="体重 kg"><el-input-number v-model="form.weightKg" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
