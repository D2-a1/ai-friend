package com.aifriend.task.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;
import com.aifriend.task.application.ConfirmTaskCommand;
import com.aifriend.task.application.CreateTaskCommand;
import com.aifriend.task.application.ReportTaskChannelResultCommand;
import com.aifriend.task.application.RecentTaskResultView;
import com.aifriend.task.application.SelectTaskCandidateCommand;
import com.aifriend.task.application.TaskChannelPartView;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskClientRecognitionEvidence;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskConfirmationResult;
import com.aifriend.task.application.TaskCreationService;
import com.aifriend.task.application.TaskSessionService;
import com.aifriend.task.application.TaskSessionView;

/**
 * 当前 owner 任务语音链与动作型确认 Controller。
 *
 * <p>确认接口只返回有限微信动作计划；本 Controller 不调用微信或无障碍服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/task-sessions")
public class TasksController {

    private final TaskCreationService creationService;
    private final TaskSessionService sessionService;

    /**
     * 创建任务 Controller。
     *
     * @param creationService 任务创建语音链服务
     * @param sessionService 任务状态服务
     */
    public TasksController(
            TaskCreationService creationService,
            TaskSessionService sessionService) {
        this.creationService = creationService;
        this.sessionService = sessionService;
    }

    /**
     * 创建方言任务并处理至当前安全状态。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 创建幂等键
     * @param request TASK 音频和客户端版本上下文
     * @return 新任务状态
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskSessionView> createTaskSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody CreateTaskSessionReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        TaskClientContextReq context = request.clientContext();
        TaskClientRecognitionEvidence recognition = request.basicRecognition() == null
                ? null : new TaskClientRecognitionEvidence(
                        request.basicRecognition().transcript(),
                        request.basicRecognition().confidence(),
                        request.basicRecognition().modelVersion(),
                        request.basicRecognition().modelArchiveSha256(),
                        request.basicRecognition().words().stream()
                                .map(word -> new TaskRecognizedWord(
                                        word.text(), word.startMs(), word.endMs(),
                                        word.confidence()))
                                .toList());
        return ApiResponse.success(creationService.create(
                currentUser.id(), idempotencyKey,
                new CreateTaskCommand(
                        request.clientTaskId(), request.audioObjectId(),
                        request.previousConfirmedContactId(),
                        new TaskClientContext(
                                context.appVersion(), context.wechatVersion(),
                                context.ruleVersion(), context.dialectCode(),
                                context.dialectPackageVersion(),
                                context.mandarinAssistVersion(),
                                context.fusionRuleVersion(),
                                context.templateModelVersion(),
                                context.thresholdVersion(), recognition))));
    }

    /**
     * 查看当前 owner 最近二十条已结束任务的最小结果。
     *
     * @param jwt 已验证 JWT
     * @return 不含录音、正文和联系人的最近任务结果
     */
    @GetMapping("/recent")
    public ApiResponse<List<RecentTaskResultView>> listRecentTaskResults(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(
                sessionService.listRecentResults(CurrentUser.from(jwt).id()));
    }

    /**
     * 查询 owner 范围任务最新状态。
     *
     * @param jwt 已验证 JWT
     * @param id ts_ 前缀任务编号
     * @return 最新状态
     */
    @GetMapping("/{id}")
    public ApiResponse<TaskSessionView> getTaskSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^ts_[A-Za-z0-9]+$") String id) {
        return ApiResponse.success(
                sessionService.get(CurrentUser.from(jwt).id(), id));
    }

    /**
     * 选择当前会话返回的候选联系人。
     *
     * @param jwt 已验证 JWT
     * @param id ts_ 前缀任务编号
     * @param idempotencyKey 选择幂等键
     * @param request 候选编号和预期版本
     * @return 等待动作型确认的新版本会话
     */
    @PostMapping("/{id}/selections")
    public ApiResponse<TaskSessionView> selectTaskCandidate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^ts_[A-Za-z0-9]+$") String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody TaskSelectionReq request) {
        return ApiResponse.success(sessionService.select(
                CurrentUser.from(jwt).id(), id, idempotencyKey,
                new SelectTaskCandidateCommand(
                        request.candidateId(), request.expectedVersion())));
    }

    /**
     * 使用个人安全指令模板确认、拒绝或取消任务。
     *
     * @param jwt 已验证 JWT
     * @param id ts_ 前缀任务编号
     * @param idempotencyKey 确认幂等键
     * @param request 动作型确认请求
     * @return 更新会话与可空有限微信动作计划
     */
    @PostMapping("/{id}/confirmations")
    public ApiResponse<TaskConfirmationResult> confirmTaskSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^ts_[A-Za-z0-9]+$") String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody TaskConfirmationReq request) {
        return ApiResponse.success(sessionService.confirm(
                CurrentUser.from(jwt).id(), id, idempotencyKey,
                new ConfirmTaskCommand(
                        request.action(), request.expectedVersion(),
                        request.summaryHash(), request.recognizedTemplateId(),
                        request.recognizedAt())));
    }

    /**
     * 上报有限动作计划的受控微信渠道结果。
     *
     * @param jwt 已验证 JWT
     * @param id ts_ 前缀任务编号
     * @param idempotencyKey 上报幂等键
     * @param request 受控渠道结果
     * @return 任务终态
     */
    @PostMapping("/{id}/channel-results")
    public ApiResponse<TaskSessionView> reportTaskChannelResult(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^ts_[A-Za-z0-9]+$") String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody TaskChannelResultReq request) {
        TaskSessionView view = sessionService.reportChannelResult(
                CurrentUser.from(jwt).id(), id, idempotencyKey,
                new ReportTaskChannelResultCommand(
                        request.planId(), request.summaryHash(), request.result(),
                        request.parts().stream().map(part -> new TaskChannelPartView(
                                part.part(), part.result(), part.evidenceCode())).toList(),
                        request.ruleVersion(), request.occurredAt()));
        String code = view.state().name().equals("PARTIAL")
                ? "WECHAT_PARTIAL_SUCCESS" : "OK";
        return new ApiResponse<>(code, "success", view,
                com.aifriend.shared.api.TraceContext.currentTraceId());
    }
}
