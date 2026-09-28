<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const COLUMNS = [
  { value: 'BUILDING', label: '在建' },
  { value: 'RAISING', label: '在养' },
  { value: 'SELLING', label: '出栏中' },
  { value: 'CLEARED', label: '已清栏' }
]
const BREEDS = [
  { value: 'YORKSHIRE', label: '大白' },
  { value: 'LANDRACE', label: '长白' },
  { value: 'DUROC', label: '杜洛克' },
  { value: 'PIETRAIN', label: '皮特兰' },
  { value: 'BERKSHIRE', label: '巴克夏' }
]

const batches = ref([])
const houses = ref([])
const shipCount = ref({})
const dialog = ref(false)
const form = ref(emptyForm())

function emptyForm () {
  return {
    batchNo: '', batchName: '', houseId: null, breed: 'LANDRACE', intakeCount: 100,
    startDate: '', expectDate: '', buyer: '', avgWeightKg: null, note: ''
  }
}

const grouped = computed(() => {
  const map = {}
  for (const col of COLUMNS) map[col.value] = []
  for (const b of batches.value) {
    if (map[b.status]) map[b.status].push(b)
  }
  return map
})

const summary = computed(() => {
  let intake = 0
  let sold = 0
  for (const b of batches.value) {
    intake += b.intakeCount
    sold += b.soldCount
  }
  return { intake, sold, rate: intake ? Math.round((sold * 100) / intake) : 0 }
})

async function load () {
  batches.value = await api.batch.list()
  if (!houses.value.length) houses.value = await api.house.list()
}

async function advance (batch) {
  try {
    await api.batch.advance(batch.id)
    ElMessage.success(`${batch.batchNo} 已推进到${batch.nextStatusName}`)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function ship (batch) {
  const count = shipCount.value[batch.id]
  try {
    await api.batch.ship(batch.id, count)
    ElMessage.success(`${batch.batchNo} 出栏登记 ${count} 头`)
    shipCount.value[batch.id] = null
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submit () {
  try {
    await api.batch.create(form.value)
    ElMessage.success('出栏批次已创建')
    dialog.value = false
    form.value = emptyForm()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeBatch (batch) {
  await ElMessageBox.confirm(`确认删除 ${batch.batchNo}？`, '删除批次', { type: 'warning' })
  await api.batch.remove(batch.id)
  ElMessage.success('批次已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">出栏批次</span>
      <span class="view-tip">卡片流水线：卡片底部一个按钮推进状态（在建 → 在养 → 出栏中 → 已清栏）</span>
    </div>

    <div class="chip-bar">
      <span class="chip on">入栏合计 {{ summary.intake }} 头</span>
      <span class="chip">已出栏 {{ summary.sold }} 头</span>
      <span class="chip">出栏进度 {{ summary.rate }}%</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="dialog = true">新建批次</el-button>
    </div>

    <div class="pipeline">
      <div v-for="col in COLUMNS" :key="col.value" class="pipe-col">
        <div class="pipe-col-head">
          <span class="pipe-col-title">{{ col.label }}</span>
          <span class="tag">{{ grouped[col.value].length }} 批</span>
        </div>

        <div v-for="batch in grouped[col.value]" :key="batch.id" class="batch-card">
          <div class="bc-no">{{ batch.batchNo }}</div>
          <div class="bc-name">{{ batch.batchName }} · {{ batch.houseCode || '未指定栋舍' }} · {{ batch.breedName }}</div>
          <div class="bc-nums">
            <span>入栏 <b>{{ batch.intakeCount }}</b></span>
            <span>已出栏 <b>{{ batch.soldCount }}</b></span>
            <span>死淘 <b>{{ batch.deadCount }}</b></span>
            <span>存栏 <b>{{ batch.remainCount }}</b></span>
          </div>
          <div class="load-bar" :class="{ full: batch.soldRate >= 1 }">
            <i :style="{ width: Math.round(batch.soldRate * 100) + '%' }"></i>
          </div>
          <div class="bc-name" style="margin-top: 6px">
            均重 {{ batch.avgWeightKg || '—' }}kg · 预计 {{ batch.expectDate || '—' }} · {{ batch.buyer || '未定收购方' }}
          </div>

          <div v-if="batch.status === 'SELLING'" style="display: flex; gap: 6px; margin-top: 8px">
            <el-input-number v-model="shipCount[batch.id]" :min="1" :max="batch.remainCount" size="small" style="width: 130px" placeholder="头数" />
            <el-button size="small" @click="ship(batch)">登记出栏</el-button>
          </div>

          <!-- 卡片底部单按钮推进状态 -->
          <div class="bc-foot">
            <el-button v-if="batch.nextStatus" type="primary" size="small" style="width: 100%" @click="advance(batch)">
              推进到「{{ batch.nextStatusName }}」
            </el-button>
            <el-button v-else size="small" style="width: 100%" disabled>已清栏 · 终态</el-button>
          </div>
          <div style="text-align: right; margin-top: 6px">
            <el-button size="small" link type="danger" @click="removeBatch(batch)">删除</el-button>
          </div>
        </div>

        <div v-if="!grouped[col.value].length" class="empty-tip">暂无批次</div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建出栏批次" width="440px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="批次号"><el-input v-model="form.batchNo" placeholder="如 BATCH-2609-03" /></el-form-item>
        <el-form-item label="批次名称"><el-input v-model="form.batchName" placeholder="如 九月育肥一批" /></el-form-item>
        <el-form-item label="所在栋舍">
          <el-select v-model="form.houseId" clearable style="width: 100%">
            <el-option v-for="h in houses" :key="h.id" :label="h.houseCode + ' ' + h.houseName" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="品种">
          <el-select v-model="form.breed" style="width: 100%">
            <el-option v-for="b in BREEDS" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="入栏头数"><el-input-number v-model="form.intakeCount" :min="1" :max="5000" /></el-form-item>
        <el-form-item label="入栏日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="预计出栏">
          <el-date-picker v-model="form.expectDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收购方"><el-input v-model="form.buyer" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
