package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.House;
import com.hog.farm.domain.HouseStatusLog;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.HouseRequest;
import com.hog.farm.dto.HouseStatusRequest;
import com.hog.farm.dto.PenRequest;
import com.hog.farm.dto.Views.HouseAnomalyVO;
import com.hog.farm.dto.Views.HousePigItemVO;
import com.hog.farm.dto.Views.HouseStatusPreviewVO;
import com.hog.farm.dto.Views.HouseStatusResultVO;
import com.hog.farm.dto.Views.HouseVO;
import com.hog.farm.dto.Views.PenVO;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.HouseStatusLogRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 栋舍与栏位：栋舍编号唯一、类型 5 选 1、栏位容量 > 0。
 * 状态链路（检修处置规则）：
 *   检修（MAINTAIN）—— 立即生效、原地照护：保留现有存栏，冻结新增（入栏 / 转入 / 恢复到该栋舍）；
 *   停用（DISABLED）—— 先腾空再生效：全部猪只迁出且栏位无未完操作（清洗消毒 / 维修）才生效，
 *   未腾空时停留在「待清空」（DRAINING），同样冻结新增；
 *   恢复在用（ACTIVE）—— 重新评估栏位维修状态与实际存栏，遗留的超容 / 位置不明 / 计数不符
 *   保留为待处理异常，不因恢复操作被静默清除。
 * 并发口径：状态切换与所有猪只进入入口共用栋舍行锁（先锁栋舍再动猪 / 栏），
 * 同一次切换凭 request_no 幂等，不会重复生效。
 */
@Service
public class HouseService {

    private final HouseRepository houseRepository;
    private final PenRepository penRepository;
    private final PigRepository pigRepository;
    private final HouseStatusLogRepository statusLogRepository;

    public HouseService(HouseRepository houseRepository, PenRepository penRepository,
                        PigRepository pigRepository, HouseStatusLogRepository statusLogRepository) {
        this.houseRepository = houseRepository;
        this.penRepository = penRepository;
        this.pigRepository = pigRepository;
        this.statusLogRepository = statusLogRepository;
    }

    public List<House> list() {
        return houseRepository.findAllByOrderByHouseCodeAsc();
    }

    public List<Pen> pens(Long houseId) {
        if (houseId == null) {
            return penRepository.findAllByOrderByPenCodeAsc();
        }
        return penRepository.findByHouseIdOrderByPenCodeAsc(houseId);
    }

    public House get(Long id) {
        return houseRepository.findById(id).orElseThrow(() -> new BizException("栋舍不存在：" + id));
    }

    public Pen getPen(Long id) {
        return penRepository.findById(id).orElseThrow(() -> new BizException("栏位不存在：" + id));
    }

    /** 栋舍平面格子图：栋舍 → 若干栏位格子（每格带存栏 / 容量 / 存栏率），栋舍带受限原因与处置进度 */
    public List<HouseVO> grid() {
        List<House> houses = houseRepository.findAllByOrderByHouseCodeAsc();
        List<Pen> allPens = penRepository.findAllByOrderByPenCodeAsc();
        List<HouseVO> result = new ArrayList<>();
        for (House house : houses) {
            HouseVO vo = toHouseVO(house);
            int capacity = 0;
            int pigCount = 0;
            for (Pen pen : allPens) {
                if (house.getId().equals(pen.getHouseId())) {
                    vo.getPens().add(toPenVO(pen, house));
                    capacity += nvl(pen.getCapacity());
                    pigCount += nvl(pen.getPigCount());
                }
            }
            vo.setCapacity(capacity);
            vo.setPigCount(pigCount);
            vo.setUsageRate(capacity <= 0 ? 0D : Math.min(1D, pigCount * 1D / capacity));
            fillRestriction(vo, house, evaluate(house));
            result.add(vo);
        }
        return result;
    }

    /** 单个栏位格子（前端点格看存栏时用） */
    public PenVO penVO(Long penId) {
        Pen pen = getPen(penId);
        House house = pen.getHouseId() == null ? null : houseRepository.findById(pen.getHouseId()).orElse(null);
        return toPenVO(pen, house);
    }

    @Transactional
    public House create(HouseRequest request) {
        request.validateTypeAndStatus();
        if (!StringUtils.hasText(request.getHouseCode())) {
            throw new BizException("栋舍编号不能为空");
        }
        if (!Dict.isValidHouseType(request.getHouseType())) {
            throw new BizException("栋舍类型只能为 配怀 / 分娩 / 保育 / 育肥 / 隔离");
        }
        if (request.getStatus() != null && !"ACTIVE".equals(request.getStatus())) {
            throw new BizException("新建栋舍初始状态只能为在用；如需检修 / 停用，请创建后通过状态切换办理");
        }
        if (houseRepository.existsByHouseCode(request.getHouseCode())) {
            throw new BizException("栋舍编号已存在：" + request.getHouseCode());
        }
        House house = new House();
        apply(house, request);
        // 默认值写在 create 里，实体字段不带默认值；初始状态只能在用
        house.setStatus("ACTIVE");
        house.setStatusChangedAt(LocalDateTime.now());
        if (house.getPenCount() == null) {
            house.setPenCount(0);
        }
        if (house.getBuiltDate() == null) {
            house.setBuiltDate(LocalDate.now());
        }
        return houseRepository.save(house);
    }

    @Transactional
    public House update(Long id, HouseRequest request) {
        House house = get(id);
        request.validateTypeAndStatus();
        if (request.getStatus() != null && !request.getStatus().equals(house.getStatus())) {
            throw new BizException("栋舍状态不能随资料直接改，请使用状态切换（检修 / 停用 / 恢复在用）并填写原因");
        }
        if (StringUtils.hasText(request.getHouseCode())
                && !request.getHouseCode().equals(house.getHouseCode())
                && houseRepository.existsByHouseCode(request.getHouseCode())) {
            throw new BizException("栋舍编号已存在：" + request.getHouseCode());
        }
        if (request.getHouseCode() != null) {
            house.setHouseCode(request.getHouseCode());
        }
        if (request.getHouseName() != null) {
            house.setHouseName(request.getHouseName());
        }
        if (request.getHouseType() != null) {
            house.setHouseType(request.getHouseType());
        }
        ifNotNull(request.getAreaSqm(), house::setAreaSqm);
        ifNotNull(request.getKeeper(), house::setKeeper);
        ifNotNull(request.getBuiltDate(), house::setBuiltDate);
        ifNotNull(request.getNote(), house::setNote);
        return houseRepository.save(house);
    }

    // ---------------- 状态链路 ----------------

    /**
     * 状态切换预览：操作员确认前展示受影响栏位、猪只清单、阻塞原因与确认后将生效的结果。
     */
    @Transactional(readOnly = true)
    public HouseStatusPreviewVO statusPreview(Long id, String target) {
        assertValidTarget(target);
        House house = get(id);
        Evaluation eval = evaluate(house);
        String current = currentStatus(house);

        HouseStatusPreviewVO vo = new HouseStatusPreviewVO();
        vo.setHouseId(house.getId());
        vo.setHouseCode(house.getHouseCode());
        vo.setCurrentStatus(current);
        vo.setCurrentStatusName(Dict.houseStatusName(current));
        vo.setTargetStatus(target);
        vo.setTargetStatusName(Dict.houseStatusName(target));
        vo.setWillBecome(willBecome(current, target, eval));
        vo.setWillBecomeName(Dict.houseStatusName(vo.getWillBecome()));
        vo.setEntryFrozenAfter(Dict.isHouseEntryFrozen(vo.getWillBecome()));
        vo.setDecisionText(decisionText(house, current, target, vo.getWillBecome(), eval));
        vo.setTotalPigs(eval.occupants.size());
        vo.setUnfinishedPens(eval.unfinishedPens);
        vo.getBlockers().addAll(eval.blockers);
        vo.getAnomalies().addAll(eval.anomalies);
        for (Pen pen : eval.pens) {
            vo.getPens().add(toPenVO(pen, house));
        }
        for (Pig pig : eval.occupants) {
            vo.getPigs().add(toPigItem(pig, eval.penCodes));
        }
        return vo;
    }

    /**
     * 状态切换：检修立即生效原地照护；停用先腾空再生效（未腾空转「待清空」）；
     * 恢复在用重新评估并保留遗留异常。同一 request_no 重复提交直接重放，不重复生效。
     */
    @Transactional
    public HouseStatusResultVO changeStatus(Long id, HouseStatusRequest request) {
        String target = request == null ? null : request.getTarget();
        assertValidTarget(target);
        String requestNo = request.getRequestNo();
        if (StringUtils.hasText(requestNo)) {
            HouseStatusLog replay = statusLogRepository.findByRequestNo(requestNo).orElse(null);
            if (replay != null) {
                return replayResult(replay);
            }
        }
        // 与猪只进入入口共用栋舍行锁：状态受限与新增猪只不会同时成立
        House house = houseRepository.lockById(id)
                .orElseThrow(() -> new BizException("栋舍不存在：" + id));
        if (StringUtils.hasText(requestNo)) {
            // 拿到锁后再查一次：前一个同事务可能刚用同一幂等键提交过
            HouseStatusLog replay = statusLogRepository.findByRequestNo(requestNo).orElse(null);
            if (replay != null) {
                return replayResult(replay);
            }
        }
        String current = currentStatus(house);
        if ("DISABLED".equals(current) && "MAINTAIN".equals(target)) {
            throw new BizException("已停用栋舍请先恢复在用，再申请检修");
        }
        Evaluation eval = evaluate(house);

        HouseStatusResultVO result = new HouseStatusResultVO();
        result.setHouseId(house.getId());
        result.setHouseCode(house.getHouseCode());
        result.setRequestedStatus(target);
        result.setRequestedStatusName(Dict.houseStatusName(target));

        String effective = willBecome(current, target, eval);
        if (effective.equals(current)) {
            // 目标即现状，或停用条件未满足仍停留待清空：不重复生效、不落台账
            result.setNoop(Boolean.TRUE);
            result.setEffectiveStatus(current);
            result.setEffectiveStatusName(Dict.houseStatusName(current));
            result.setMessage(target.equals(current)
                    ? "栋舍已处于「" + Dict.houseStatusName(current) + "」，本次未重复生效"
                    : "停用条件未满足：" + String.join("；", eval.blockers) + "，栋舍仍停留「待清空」");
            fillEval(result, eval);
            result.setHouse(toGridVO(house, eval));
            return result;
        }

        house.setStatus(effective);
        house.setStatusReason(StringUtils.hasText(request.getReason()) ? request.getReason().trim() : null);
        house.setStatusChangedAt(LocalDateTime.now());
        houseRepository.save(house);

        HouseStatusLog log = new HouseStatusLog();
        log.setHouseId(house.getId());
        log.setRequestNo(StringUtils.hasText(requestNo) ? requestNo.trim() : newRequestNo());
        log.setFromStatus(current);
        log.setRequestedStatus(target);
        log.setToStatus(effective);
        log.setReason(house.getStatusReason());
        log.setOperator(StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : "场长");
        log.setPigCount(eval.occupants.size());
        log.setUnfinishedPens(eval.unfinishedPens);
        log.setAnomalyCount(eval.anomalies.size());
        log.setDetail(eval.blockers.isEmpty() ? null : String.join("；", eval.blockers));
        log.setCreatedAt(LocalDateTime.now());
        statusLogRepository.save(log);

        result.setEffectiveStatus(effective);
        result.setEffectiveStatusName(Dict.houseStatusName(effective));
        result.setMessage(switchMessage(house, target, effective, eval));
        fillEval(result, eval);
        result.setHouse(toGridVO(house, eval));
        return result;
    }

    /** 允许的目标状态：在用 / 检修 / 停用；待清空由系统落位，不接受直接指定 */
    private void assertValidTarget(String target) {
        if (!Dict.isValidHouseStatus(target) || "DRAINING".equals(target)) {
            throw new BizException("目标状态只能为 在用 / 检修 / 停用：" + target);
        }
    }

    /** 按状态链路规则计算确认后实际生效的状态 */
    private String willBecome(String current, String target, Evaluation eval) {
        if (target.equals(current)) {
            return current;
        }
        switch (target) {
            case "MAINTAIN":
                // 已停用栋舍须先恢复在用，再申请检修
                return "DISABLED".equals(current) ? current : "MAINTAIN";
            case "DISABLED":
                // 先腾空再生效：有存栏或栏位有未完操作时停留「待清空」
                return eval.blockers.isEmpty() ? "DISABLED" : "DRAINING";
            case "ACTIVE":
                return "ACTIVE";
            default:
                throw new BizException("目标状态只能为 在用 / 检修 / 停用：" + target);
        }
    }

    private String decisionText(House house, String current, String target, String willBecome, Evaluation eval) {
        if (target.equals(current)) {
            return "栋舍已处于「" + Dict.houseStatusName(current) + "」，确认后不会重复生效";
        }
        switch (target) {
            case "MAINTAIN":
                if ("DISABLED".equals(current)) {
                    return "已停用栋舍需先恢复在用，才能申请检修";
                }
                return "检修立即生效：冻结新增入栏 / 转入 / 恢复到该栋舍，现有 "
                        + eval.occupants.size() + " 头猪原地照护，饲喂与健康事件照常登记";
            case "DISABLED":
                if ("DRAINING".equals(willBecome)) {
                    return "仍有 " + eval.occupants.size() + " 头猪在舍或栏位有未完操作：先进入「待清空」并冻结新增，"
                            + "全部迁出且栏位无未完操作后再次确认，停用才生效";
                }
                return "栋舍已腾空且栏位无未完操作，停用立即生效，生效后禁止一切猪只进入";
            case "ACTIVE":
                return eval.anomalies.isEmpty()
                        ? "恢复在用前已重新评估栏位维修状态与实际存栏，无遗留异常"
                        : "恢复在用前已重新评估：" + eval.anomalies.size()
                        + " 项遗留异常（超容 / 位置不明 / 计数不符）将保留为待处理，不会因恢复被清除";
            default:
                return "";
        }
    }

    private String switchMessage(House house, String target, String effective, Evaluation eval) {
        switch (effective) {
            case "MAINTAIN":
                return "检修已生效：冻结新增，" + eval.occupants.size() + " 头猪原地照护，饲喂与健康事件照常登记";
            case "DRAINING":
                return "已进入待清空：冻结新增；" + String.join("；", eval.blockers)
                        + "。全部迁出且栏位无未完操作后再次确认停用";
            case "DISABLED":
                return "栋舍已腾空且栏位无未完操作，停用已生效";
            case "ACTIVE":
                return eval.anomalies.isEmpty()
                        ? "已恢复在用，评估无遗留异常"
                        : "已恢复在用；" + eval.anomalies.size() + " 项遗留异常保留为待处理，未被清除";
            default:
                return "栋舍状态已更新";
        }
    }

    /** 幂等重放：同一 request_no 的切换已生效过，直接返回当时结论与当前状态 */
    private HouseStatusResultVO replayResult(HouseStatusLog log) {
        House house = get(log.getHouseId());
        Evaluation eval = evaluate(house);
        HouseStatusResultVO result = new HouseStatusResultVO();
        result.setHouseId(house.getId());
        result.setHouseCode(house.getHouseCode());
        result.setRequestedStatus(log.getRequestedStatus());
        result.setRequestedStatusName(Dict.houseStatusName(log.getRequestedStatus()));
        result.setEffectiveStatus(log.getToStatus());
        result.setEffectiveStatusName(Dict.houseStatusName(log.getToStatus()));
        result.setReplayed(Boolean.TRUE);
        result.setMessage("该次切换已于 " + (log.getCreatedAt() == null ? "此前" : log.getCreatedAt())
                + " 生效为「" + Dict.houseStatusName(log.getToStatus()) + "」，本次为幂等重放，未重复生效");
        fillEval(result, eval);
        result.setHouse(toGridVO(house, eval));
        return result;
    }

    private void fillEval(HouseStatusResultVO result, Evaluation eval) {
        result.setRemainingPigs(eval.occupants.size());
        result.setUnfinishedPens(eval.unfinishedPens);
        result.getBlockers().addAll(eval.blockers);
        result.getAnomalies().addAll(eval.anomalies);
    }

    @Transactional
    public void remove(Long id) {
        House house = get(id);
        List<Pen> pens = penRepository.findByHouseIdOrderByPenCodeAsc(id);
        if (!pens.isEmpty()) {
            throw new BizException("栋舍下仍有 " + pens.size() + " 个栏位，请先删除栏位");
        }
        houseRepository.delete(house);
    }

    // ---------------- 栏位 ----------------

    @Transactional
    public Pen createPen(PenRequest request) {
        if (request.getHouseId() == null) {
            throw new BizException("请选择栏位所属栋舍");
        }
        House house = get(request.getHouseId());
        if (!StringUtils.hasText(request.getPenCode())) {
            throw new BizException("栏位编号不能为空");
        }
        if (penRepository.existsByPenCode(request.getPenCode())) {
            throw new BizException("栏位编号已存在：" + request.getPenCode());
        }
        if (request.getCapacity() == null || request.getCapacity() <= 0) {
            throw new BizException("栏位容量必须大于 0");
        }
        if (request.getCapacity() > Dict.MAX_PEN_CAPACITY) {
            throw new BizException("单个栏位容量不得超过 " + Dict.MAX_PEN_CAPACITY + " 头");
        }
        if (request.getStatus() != null && !Dict.isValidPenStatus(request.getStatus())) {
            throw new BizException("栏位状态非法：" + request.getStatus());
        }
        Pen pen = new Pen();
        pen.setHouseId(house.getId());
        pen.setPenCode(request.getPenCode());
        pen.setPenName(request.getPenName());
        pen.setCapacity(request.getCapacity());
        pen.setPigCount(0);
        pen.setStatus(request.getStatus() == null ? "NORMAL" : request.getStatus());
        pen.setNote(request.getNote());
        Pen saved = penRepository.save(pen);
        house.setPenCount(nvl(house.getPenCount()) + 1);
        houseRepository.save(house);
        return saved;
    }

    @Transactional
    public Pen updatePen(Long id, PenRequest request) {
        Pen pen = getPen(id);
        if (StringUtils.hasText(request.getPenCode())
                && !request.getPenCode().equals(pen.getPenCode())
                && penRepository.existsByPenCode(request.getPenCode())) {
            throw new BizException("栏位编号已存在：" + request.getPenCode());
        }
        if (request.getCapacity() != null) {
            if (request.getCapacity() <= 0) {
                throw new BizException("栏位容量必须大于 0");
            }
            if (request.getCapacity() > Dict.MAX_PEN_CAPACITY) {
                throw new BizException("单个栏位容量不得超过 " + Dict.MAX_PEN_CAPACITY + " 头");
            }
            if (request.getCapacity() < nvl(pen.getPigCount())) {
                throw new BizException("容量不能小于当前存栏 " + nvl(pen.getPigCount()) + " 头");
            }
        }
        if (request.getStatus() != null && !Dict.isValidPenStatus(request.getStatus())) {
            throw new BizException("栏位状态非法：" + request.getStatus());
        }
        ifNotNull(request.getPenCode(), pen::setPenCode);
        ifNotNull(request.getPenName(), pen::setPenName);
        ifNotNull(request.getCapacity(), pen::setCapacity);
        ifNotNull(request.getStatus(), pen::setStatus);
        ifNotNull(request.getNote(), pen::setNote);
        Pen saved = penRepository.save(pen);
        refreshPenStatus(saved);
        return penRepository.save(saved);
    }

    @Transactional
    public void removePen(Long id) {
        Pen pen = getPen(id);
        if (nvl(pen.getPigCount()) > 0) {
            throw new BizException("栏位内仍有 " + pen.getPigCount() + " 头猪，不能删除");
        }
        penRepository.delete(pen);
        if (pen.getHouseId() != null) {
            House house = houseRepository.findById(pen.getHouseId()).orElse(null);
            if (house != null) {
                house.setPenCount(Math.max(0, nvl(house.getPenCount()) - 1));
                houseRepository.save(house);
            }
        }
    }

    // ---------------- 猪只进入入口（统一闸门） ----------------

    /**
     * 转入校验：栏位存在 → 所属栋舍未受限 → 剩余容量足够。
     * 转栏、新建猪只入栏、恢复到该栋舍都走这里；与状态切换共用栋舍行锁，
     * 保证「状态已受限却多出新猪」不会出现。
     */
    public void assertCanReceive(Long penId, int heads) {
        Pen pen = getPen(penId);
        if (pen.getHouseId() != null) {
            House house = houseRepository.lockById(pen.getHouseId()).orElse(null);
            if (house != null) {
                assertHouseEntryOpen(house);
            }
        }
        if ("REPAIR".equals(pen.getStatus())) {
            throw new BizException("栏位 " + pen.getPenCode() + " 维修中，不能转入猪只");
        }
        int need = heads <= 0 ? 1 : heads;
        if (pen.getRemaining() < need) {
            throw new BizException("栏位 " + pen.getPenCode() + " 剩余容量不足：仅剩 "
                    + pen.getRemaining() + " 个位置，本次需要 " + need + " 个");
        }
    }

    /** 只指定栋舍（未指定栏位）的入栏入口，同样要过栋舍闸门 */
    public void assertHouseCanReceive(Long houseId) {
        if (houseId == null) {
            return;
        }
        House house = houseRepository.lockById(houseId).orElse(null);
        if (house != null) {
            assertHouseEntryOpen(house);
        }
    }

    /** 冻结新增的统一口径：检修 / 待清空 / 停用都拒绝新猪进入 */
    private void assertHouseEntryOpen(House house) {
        String status = house.getStatus();
        if ("MAINTAIN".equals(status)) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 检修中（原地照护），已冻结新增，不允许入栏 / 转入");
        }
        if ("DRAINING".equals(status)) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 停用待清空，已冻结新增，不允许入栏 / 转入");
        }
        if ("DISABLED".equals(status)) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 已停用，其下栏位不允许转入猪只");
        }
    }

    /** 栏位存栏数增减（转栏 / 入栏 / 出栏共用），并同步满栏状态 */
    @Transactional
    public void changePenPigCount(Long penId, int delta) {
        if (penId == null) {
            return;
        }
        Pen pen = penRepository.findById(penId).orElse(null);
        if (pen == null) {
            return;
        }
        pen.setPigCount(Math.max(0, nvl(pen.getPigCount()) + delta));
        refreshPenStatus(pen);
        penRepository.save(pen);
    }

    private void refreshPenStatus(Pen pen) {
        if (pen.getCapacity() == null || pen.getCapacity() <= 0) {
            return;
        }
        if ("CLEANING".equals(pen.getStatus()) || "REPAIR".equals(pen.getStatus())) {
            return;
        }
        pen.setStatus(nvl(pen.getPigCount()) >= pen.getCapacity() ? "FULL" : "NORMAL");
    }

    // ---------------- 状态评估（受限原因 / 阻塞 / 待处理异常） ----------------

    /** 一次评估的内部结果 */
    private static final class Evaluation {
        private List<Pen> pens = new ArrayList<>();
        private Map<Long, String> penCodes = new LinkedHashMap<>();
        /** 在舍猪只（非终态：栏位内实际猪只 + 位置不明猪只），停用前必须全部迁出 */
        private List<Pig> occupants = new ArrayList<>();
        /** 未完操作（清洗消毒 / 维修）栏位数 */
        private int unfinishedPens;
        /** 停用阻塞原因 */
        private List<String> blockers = new ArrayList<>();
        /** 待处理异常：超容 / 位置不明 / 计数不符 */
        private List<HouseAnomalyVO> anomalies = new ArrayList<>();
    }

    /**
     * 评估栋舍：受影响栏位、在舍猪只、停用阻塞原因、待处理异常。
     * 只读计算，任何时刻刷新都与实际数据一致；异常只呈现不修复，不会被静默清除。
     */
    private Evaluation evaluate(House house) {
        Evaluation eval = new Evaluation();
        eval.pens = penRepository.findByHouseIdOrderByPenCodeAsc(house.getId());
        List<Long> penIds = new ArrayList<>();
        Set<Long> penIdSet = new LinkedHashSet<>();
        for (Pen pen : eval.pens) {
            penIds.add(pen.getId());
            penIdSet.add(pen.getId());
            eval.penCodes.put(pen.getId(), pen.getPenCode());
        }

        // 栏位内实际猪只 + 栋舍标记的猪只，两边合并找「在舍」与「位置不明」
        List<Pig> inPens = penIds.isEmpty() ? List.of() : pigRepository.findByPenIdInOrderByIdAsc(penIds);
        List<Pig> markedHere = pigRepository.findByHouseIdOrderByIdAsc(house.getId());
        Map<Long, Pig> occupantMap = new LinkedHashMap<>();
        Map<Long, Integer> actualByPen = new LinkedHashMap<>();
        for (Pig pig : inPens) {
            actualByPen.merge(pig.getPenId(), 1, Integer::sum);
            if (!Dict.isTerminalPigStatus(pig.getStatus())) {
                occupantMap.putIfAbsent(pig.getId(), pig);
                if (!house.getId().equals(pig.getHouseId())) {
                    eval.anomalies.add(anomaly("LOCATION_UNKNOWN", eval.penCodes.get(pig.getPenId()),
                            "猪只 " + pig.getEarTag() + " 栏位在本舍，栋舍标记却为其他栋舍"));
                }
            }
        }
        for (Pig pig : markedHere) {
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                continue;
            }
            if (pig.getPenId() == null) {
                occupantMap.putIfAbsent(pig.getId(), pig);
                eval.anomalies.add(anomaly("LOCATION_UNKNOWN", "",
                        "猪只 " + pig.getEarTag() + " 标记在本舍但无栏位"));
            } else if (!penIdSet.contains(pig.getPenId())) {
                occupantMap.putIfAbsent(pig.getId(), pig);
                eval.anomalies.add(anomaly("LOCATION_UNKNOWN", "",
                        "猪只 " + pig.getEarTag() + " 标记在本舍，栏位却在其他栋舍"));
            }
        }
        eval.occupants = new ArrayList<>(occupantMap.values());

        // 栏位维度：超容 / 计数不符 / 未完操作
        for (Pen pen : eval.pens) {
            int ledger = nvl(pen.getPigCount());
            int actual = actualByPen.getOrDefault(pen.getId(), 0);
            if (pen.getCapacity() != null && pen.getCapacity() > 0 && ledger > pen.getCapacity()) {
                eval.anomalies.add(anomaly("OVER_CAPACITY", pen.getPenCode(),
                        "栏位 " + pen.getPenCode() + " 台账存栏 " + ledger + " 头，超容量 " + pen.getCapacity() + " 头"));
            }
            if (ledger != actual) {
                eval.anomalies.add(anomaly("COUNT_MISMATCH", pen.getPenCode(),
                        "栏位 " + pen.getPenCode() + " 台账存栏 " + ledger + " 头，实际 " + actual + " 头"));
            }
            if ("CLEANING".equals(pen.getStatus()) || "REPAIR".equals(pen.getStatus())) {
                eval.unfinishedPens++;
            }
        }

        // 停用阻塞原因：按栏位列出存栏，加上未完操作
        Map<Long, Integer> occupantByPen = new LinkedHashMap<>();
        int strayCount = 0;
        for (Pig pig : eval.occupants) {
            if (pig.getPenId() != null && penIdSet.contains(pig.getPenId())) {
                occupantByPen.merge(pig.getPenId(), 1, Integer::sum);
            } else {
                strayCount++;
            }
        }
        for (Pen pen : eval.pens) {
            Integer count = occupantByPen.get(pen.getId());
            if (count != null && count > 0) {
                eval.blockers.add("栏位 " + pen.getPenCode() + " 尚有 " + count + " 头猪待迁出");
            }
        }
        if (strayCount > 0) {
            eval.blockers.add(strayCount + " 头猪位置不明，需先落实栏位或迁出");
        }
        for (Pen pen : eval.pens) {
            if ("CLEANING".equals(pen.getStatus())) {
                eval.blockers.add("栏位 " + pen.getPenCode() + " 清洗消毒中，存在未完操作");
            } else if ("REPAIR".equals(pen.getStatus())) {
                eval.blockers.add("栏位 " + pen.getPenCode() + " 维修中，存在未完操作");
            }
        }
        return eval;
    }

    private HouseAnomalyVO anomaly(String type, String penCode, String detail) {
        HouseAnomalyVO vo = new HouseAnomalyVO();
        vo.setType(type);
        vo.setTypeName(switch (type) {
            case "OVER_CAPACITY" -> "超容";
            case "COUNT_MISMATCH" -> "计数不符";
            default -> "位置不明";
        });
        vo.setPenCode(penCode);
        vo.setDetail(detail);
        return vo;
    }

    // ---------------- 视图装配 ----------------

    private HouseVO toGridVO(House house, Evaluation eval) {
        HouseVO vo = toHouseVO(house);
        int capacity = 0;
        int pigCount = 0;
        for (Pen pen : eval.pens) {
            vo.getPens().add(toPenVO(pen, house));
            capacity += nvl(pen.getCapacity());
            pigCount += nvl(pen.getPigCount());
        }
        vo.setCapacity(capacity);
        vo.setPigCount(pigCount);
        vo.setUsageRate(capacity <= 0 ? 0D : Math.min(1D, pigCount * 1D / capacity));
        fillRestriction(vo, house, eval);
        return vo;
    }

    /** 受限原因与存栏处置进度：生效中 / 待清空 / 冻结口径，刷新即重算，与实际一致 */
    private void fillRestriction(HouseVO vo, House house, Evaluation eval) {
        String status = currentStatus(house);
        vo.setEntryFrozen(Dict.isHouseEntryFrozen(status));
        vo.setRemainingPigs(eval.occupants.size());
        vo.setUnfinishedPens(eval.unfinishedPens);
        vo.getAnomalies().addAll(eval.anomalies);
        String reason = house.getStatusReason();
        switch (status) {
            case "MAINTAIN":
                vo.setRestrictionReason("检修中：冻结新增入栏 / 转入，" + eval.occupants.size()
                        + " 头猪原地照护" + (StringUtils.hasText(reason) ? "；" + reason : ""));
                break;
            case "DRAINING":
                vo.setRestrictionReason("停用待清空：冻结新增，待迁出 " + eval.occupants.size() + " 头"
                        + (StringUtils.hasText(reason) ? "；" + reason : ""));
                vo.getBlockers().addAll(eval.blockers);
                break;
            case "DISABLED":
                vo.setRestrictionReason("已停用：禁止一切猪只进入"
                        + (StringUtils.hasText(reason) ? "；" + reason : ""));
                break;
            default:
                vo.setRestrictionReason(null);
        }
    }

    private HousePigItemVO toPigItem(Pig pig, Map<Long, String> penCodes) {
        HousePigItemVO vo = new HousePigItemVO();
        vo.setId(pig.getId());
        vo.setEarTag(pig.getEarTag());
        vo.setPenCode(pig.getPenId() == null ? "" : penCodes.getOrDefault(pig.getPenId(), "舍外栏位"));
        vo.setStatus(pig.getStatus());
        vo.setStatusName(Dict.pigStatusName(pig.getStatus()));
        return vo;
    }

    private void apply(House house, HouseRequest request) {
        house.setHouseCode(request.getHouseCode());
        house.setHouseName(request.getHouseName());
        house.setHouseType(request.getHouseType());
        house.setAreaSqm(request.getAreaSqm());
        house.setKeeper(request.getKeeper());
        house.setBuiltDate(request.getBuiltDate());
        house.setNote(request.getNote());
    }

    public HouseVO toHouseVO(House house) {
        HouseVO vo = new HouseVO();
        vo.setId(house.getId());
        vo.setHouseCode(house.getHouseCode());
        vo.setHouseName(house.getHouseName());
        vo.setHouseType(house.getHouseType());
        vo.setHouseTypeName(Dict.houseTypeName(house.getHouseType()));
        vo.setHouseTypeColor(Dict.houseTypeColor(house.getHouseType()));
        vo.setAreaSqm(house.getAreaSqm());
        vo.setKeeper(house.getKeeper());
        vo.setBuiltDate(str(house.getBuiltDate()));
        vo.setPenCount(nvl(house.getPenCount()));
        vo.setStatus(house.getStatus());
        vo.setStatusName(Dict.houseStatusName(house.getStatus()));
        vo.setStatusReason(house.getStatusReason());
        vo.setStatusChangedAt(house.getStatusChangedAt() == null ? null : house.getStatusChangedAt().toString());
        vo.setNote(house.getNote());
        vo.setDisabled(Boolean.valueOf(house.isDisabled()));
        vo.setEntryFrozen(Boolean.valueOf(Dict.isHouseEntryFrozen(house.getStatus())));
        vo.setPens(new ArrayList<>());
        return vo;
    }

    public PenVO toPenVO(Pen pen, House house) {
        PenVO vo = new PenVO();
        vo.setId(pen.getId());
        vo.setHouseId(pen.getHouseId());
        vo.setHouseCode(house == null ? "" : house.getHouseCode());
        vo.setHouseName(house == null ? "" : house.getHouseName());
        vo.setPenCode(pen.getPenCode());
        vo.setPenName(pen.getPenName());
        vo.setCapacity(nvl(pen.getCapacity()));
        vo.setPigCount(nvl(pen.getPigCount()));
        vo.setRemaining(pen.getRemaining());
        vo.setUsageRate(pen.getUsageRate());
        vo.setStatus(pen.getStatus());
        vo.setStatusName(Dict.penStatusName(pen.getStatus()));
        vo.setNote(pen.getNote());
        return vo;
    }

    private static String currentStatus(House house) {
        return house.getStatus() == null ? "ACTIVE" : house.getStatus();
    }

    private static String newRequestNo() {
        return "HST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String str(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
