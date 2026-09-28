package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.Batch;
import com.hog.farm.dto.BatchRequest;
import com.hog.farm.dto.Views.BatchVO;
import com.hog.farm.service.BatchService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 出栏批次：卡片流水线，一张卡片一个推进按钮 + 登记出栏 */
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<BatchVO>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Batch> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Batch> create(@RequestBody BatchRequest request) {
        return ApiResponse.success("出栏批次已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Batch> update(@PathVariable Long id, @RequestBody BatchRequest request) {
        return ApiResponse.success("出栏批次已更新", service.update(id, request));
    }

    /** 卡片底部单按钮：按 在建 → 在养 → 出栏中 → 已清栏 顺序推进 */
    @PostMapping("/{id}/advance")
    public ApiResponse<BatchVO> advance(@PathVariable Long id) {
        return ApiResponse.success("批次已推进", service.toVO(service.advance(id)));
    }

    /** 登记出栏头数：累计不得超过入栏头数 */
    @PostMapping("/{id}/ship")
    public ApiResponse<BatchVO> ship(@PathVariable Long id, @RequestParam Integer count) {
        return ApiResponse.success("出栏已登记", service.toVO(service.ship(id, count)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("出栏批次已删除", null);
    }
}
