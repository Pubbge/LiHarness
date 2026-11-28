package com.pubbge.liharness.trigger.http;

import com.pubbge.liharness.cases.settings.ModelCase;
import com.pubbge.liharness.domain.llm.error.LlmException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型设置接口：设置页的“模型”栏和输入框里的模型选择器都用它。
 *
 * <pre>
 * GET    /api/models                    当前模型、settings.yaml 里的服务商（含 Key 的状态，不含 Key）
 * PUT    /api/models/current            {"model":"deepseek/deepseek-v4-pro"}  切换当前模型
 * PUT    /api/models/providers/{id}     {"name","baseUrl","models":[...],"apiKey"}  添加或修改服务商
 * DELETE /api/models/providers/{id}     删除服务商和它的 Key
 * </pre>
 *
 * <p>每个接口都返回最新的全部信息，前端拿到直接刷新界面，不用再查一次。任何响应里都没有 API Key。
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelCase models;

    public ModelController(ModelCase models) {
        this.models = models;
    }

    public record SelectRequest(String model) {
    }

    @GetMapping
    public ModelCase.Overview overview() {
        return models.overview();
    }

    @PutMapping("/current")
    public ModelCase.Overview select(@RequestBody SelectRequest request) throws LlmException {
        return models.selectModel(request.model());
    }

    @PutMapping("/providers/{id}")
    public ModelCase.Overview saveProvider(@PathVariable String id, @RequestBody ModelCase.ProviderForm form) {
        return models.saveProvider(id, form);
    }

    @DeleteMapping("/providers/{id}")
    public ModelCase.Overview deleteProvider(@PathVariable String id) {
        return models.deleteProvider(id);
    }

    /** 表单填错、配置不对：400，消息直接给界面显示 */
    @ExceptionHandler({IllegalArgumentException.class, LlmException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    /** 读写配置文件失败（文件写坏了、没有权限） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> fileProblem(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
    }
}
