package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pen;
import com.hog.farm.dto.HouseRequest;
import com.hog.farm.dto.PenRequest;
import com.hog.farm.dto.Views.HouseVO;
import com.hog.farm.dto.Views.PenVO;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PenRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 栋舍与栏位：栋舍编号唯一、类型 5 选 1、栏位容量 > 0、停用栋舍不允许转入猪只。
 */
@Service
public class HouseService {

    private final HouseRepository houseRepository;
    private final PenRepository penRepository;

    public HouseService(HouseRepository houseRepository, PenRepository penRepository) {
        this.houseRepository = houseRepository;
        this.penRepository = penRepository;
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

    /** 栋舍平面格子图：栋舍 → 若干栏位格子（每格带存栏 / 容量 / 存栏率） */
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
        if (houseRepository.existsByHouseCode(request.getHouseCode())) {
            throw new BizException("栋舍编号已存在：" + request.getHouseCode());
        }
        House house = new House();
        apply(house, request);
        // 默认值写在 create 里，实体字段不带默认值
        if (house.getStatus() == null) {
            house.setStatus("ACTIVE");
        }
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
        ifNotNull(request.getStatus(), house::setStatus);
        ifNotNull(request.getNote(), house::setNote);
        return houseRepository.save(house);
    }

    /** 栋舍启用 / 停用 / 检修 */
    @Transactional
    public House changeStatus(Long id, String status) {
        if (!Dict.isValidHouseStatus(status)) {
            throw new BizException("栋舍状态只能为 在用 / 检修 / 停用：" + status);
        }
        House house = get(id);
        house.setStatus(status);
        return houseRepository.save(house);
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

    /**
     * 转入校验：栏位存在 → 所属栋舍未停用 → 剩余容量足够。
     * 转栏、新建猪只入栏都走这里，保证「栋舍停用后栏位不允许转入猪只」在服务端生效。
     */
    public void assertCanReceive(Long penId, int heads) {
        Pen pen = getPen(penId);
        if (pen.getHouseId() != null) {
            House house = houseRepository.findById(pen.getHouseId()).orElse(null);
            if (house != null && "DISABLED".equals(house.getStatus())) {
                throw new BizException("栋舍 " + house.getHouseCode() + " 已停用，其下栏位不允许转入猪只");
            }
            if (house != null && "MAINTAIN".equals(house.getStatus())) {
                throw new BizException("栋舍 " + house.getHouseCode() + " 检修中，暂时不允许转入猪只");
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

    private void apply(House house, HouseRequest request) {
        house.setHouseCode(request.getHouseCode());
        house.setHouseName(request.getHouseName());
        house.setHouseType(request.getHouseType());
        house.setAreaSqm(request.getAreaSqm());
        house.setKeeper(request.getKeeper());
        house.setBuiltDate(request.getBuiltDate());
        house.setStatus(request.getStatus());
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
        vo.setNote(house.getNote());
        vo.setDisabled(Boolean.valueOf(house.isDisabled()));
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
