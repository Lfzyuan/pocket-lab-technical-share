package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

public final class Api {
    private Api() {}
    public record Envelope<T>(String code, String message, T data, String requestId) {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    public record User(String id, String account, String name, String className, String phone, String email, String role, boolean enabled, boolean canManage, String accountRole, String grade, int borrowLimit, @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) String accountType, @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) Integer enrollmentYear) {
        public User(String id,String account,String name,String className,String phone,String email,String role,boolean enabled,boolean canManage,String accountRole,String grade,int borrowLimit,String accountType){this(id,account,name,className,phone,email,role,enabled,canManage,accountRole,grade,borrowLimit,accountType,null);}
        public User(String id,String account,String name,String className,String phone,String email,String role,boolean enabled,boolean canManage,String accountRole,String grade,int borrowLimit){this(id,account,name,className,phone,email,role,enabled,canManage,accountRole,grade,borrowLimit,"admin".equals(accountRole)?"admin":"student");}
        public User(String id,String account,String name,String className,String phone,String email,String role,boolean enabled,boolean canManage,String accountRole,String grade){this(id,account,name,className,phone,email,role,enabled,canManage,accountRole,grade,3);}
        public User(String id,String account,String name,String className,String phone,String email,String role,boolean enabled,boolean canManage,String accountRole){this(id,account,name,className,phone,email,role,enabled,canManage,accountRole,"");}
        public User(String id,String account,String name,String className,String phone,String email,String role,boolean enabled){this(id,account,name,className,phone,email,role,enabled,role.equals("reviewer"),role.equals("reviewer")?"student":role);}
        public User view(String view){return new User(id,account,name,className,phone,email,canManage && "management".equals(view)?"reviewer":accountRole,enabled,canManage,accountRole,grade,borrowLimit,accountType,enrollmentYear);}
        @com.fasterxml.jackson.annotation.JsonProperty public String studentNumber(){return "student".equals(accountType)&&account.matches("[0-9]{10}")?account:null;}
        @com.fasterxml.jackson.annotation.JsonProperty public String cohort(){return !"student".equals(accountType)?null:enrollmentYear!=null?String.format(java.util.Locale.ROOT,"%02d",enrollmentYear%100):account.matches("[0-9]{10}")?account.substring(4,6):null;}
    }
    public record Login(@NotBlank @Size(max=32) String account, @NotNull String password) {}
    public record LoginResult(String accessToken, long expiresIn, User user) {}
    public record Profile(@Size(max=100) String className, @Pattern(regexp="^$|^[0-9+() -]{5,32}$") String phone, @Email @Size(max=200) String email, @Size(max=40) String grade) {}
    public record UserCreate(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{2,32}") String account,
        @NotBlank @Size(max=80) String name, @NotBlank @Pattern(regexp="student|teacher|admin") String role,
        @NotNull String password, @Size(max=100) String className, @Pattern(regexp="^$|[0-9]{11}") String phone) {
        public UserCreate(String account,String name,String role,String password,String className){this(account,name,role,password,className,"");}
    }
    public record Enabled(@NotNull Boolean enabled) {}
    public record Equipment(String id, String name, String brand, String model, String category, String spec, String desc,
        String accessories, String location, String image, boolean active, boolean requiresApproval, boolean requiresAsset,
        int stock, int available, int reserved, int borrowed, int pending, long favoriteCount, Integer sourceTotal, Integer sourceAvailable, Integer sourceRow, long version) {}
    public record Duty(String id, LocalDate date, LocalTime startTime, LocalTime endTime, String location, boolean enabled, long version) {}
    public record DutyInput(@NotNull LocalDate date, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
        @NotBlank @Size(max=200) String location) {}
    public record BorrowCreate(@NotBlank @Size(max=36) String equipmentId, @Min(1) @Max(1) int quantity,
        @Size(max=500) String purpose, @Pattern(regexp="(?:[0-9+() -]{5,32})?") String phone,
        @Size(max=36) String slotId, @NotNull @AssertTrue Boolean agreed,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Size(max=36) String pickupProxyId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp="duty|agreed") String pickupMode,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        OffsetDateTime agreedPickupAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING,pattern="uuuu-MM-dd",lenient=com.fasterxml.jackson.annotation.OptBoolean.FALSE) LocalDate agreedPickupDate) {
        public BorrowCreate(String equipmentId,int quantity,String purpose,String phone,String slotId,Boolean agreed,String pickupProxyId,String pickupMode,OffsetDateTime agreedPickupAt){this(equipmentId,quantity,purpose,phone,slotId,agreed,pickupProxyId,pickupMode,agreedPickupAt,null);}
        public BorrowCreate(String equipmentId,int quantity,String purpose,String phone,String slotId,Boolean agreed,String pickupProxyId){this(equipmentId,quantity,purpose,phone,slotId,agreed,pickupProxyId,null,null);}
    }
    public record Action(@NotNull @PositiveOrZero Long version, @Size(max=500) String reason,
        @Size(max=100) String assetNumber, Integer quantity, Boolean identityChecked, Boolean checked,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Size(max=36) String slotId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) OffsetDateTime pickupDeadlineAt) {
        public Action(Long version,String reason,String assetNumber,Integer quantity,Boolean identityChecked,Boolean checked,String slotId){this(version,reason,assetNumber,quantity,identityChecked,checked,slotId,null);}
    }
    public record Event(String id, String action, String fromStatus, String toStatus, String actorId, String reason, OffsetDateTime at,String actorName,String actorType) {
        public Event(String id,String action,String fromStatus,String toStatus,String actorId,String reason,OffsetDateTime at){this(id,action,fromStatus,toStatus,actorId,reason,at,null,"user");}
    }
    public record Borrow(String id, String equipmentId, String userId, String user, String className, String phone,
        String purpose, String slotId, int quantity, String status, boolean requiresApproval, boolean requiresAsset,
        String assetNumber, OffsetDateTime createdAt, OffsetDateTime approvedAt, OffsetDateTime issuedAt,
        LocalDate dueDate, OffsetDateTime returnedAt, OffsetDateTime closedAt, int renewCount, long version, List<String> allowedActions, List<Event> events,
        String pickupProxyId, String pickupProxyName, String pickupProxyAccount, Duty pickupSlot,
        String pickupMode, OffsetDateTime agreedPickupAt, String userAccount, String equipmentName, OffsetDateTime pickupDeadlineAt,LocalDate agreedPickupDate) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Integer nextRenewMonths(){return LoanPolicy.nextRenewMonths(renewCount);}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public OffsetDateTime returnRequestedAt(){var event=ReturnPolicy.latestRequest(events);return event==null?null:event.at();}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public OffsetDateTime returnDeadlineAt(){return ReturnPolicy.deadline(events);}
    }
    public static <T> Envelope<T> ok(T data, HttpServletRequest req) {
        return new Envelope<>("OK", "成功", data, (String) req.getAttribute("requestId"));
    }
    public static class Failure extends RuntimeException {
        public final int status;
        public final String code;
        public Failure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    }
    public static Failure invalid(String message) { return new Failure(400,"VALIDATION_ERROR",message); }
    public static Failure conflict(String message) { return new Failure(409,"STATE_CONFLICT",message); }
    public static Failure forbidden() { return new Failure(403,"FORBIDDEN","没有此操作权限"); }
    public static Failure missing() { return new Failure(404,"NOT_FOUND","记录不存在"); }
    public static Failure unauthorized() { return new Failure(401,"UNAUTHORIZED","请重新登录"); }

    @RestControllerAdvice
    public static class Errors {
        private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Errors.class);
        @ExceptionHandler(Failure.class)
        ResponseEntity<Envelope<Void>> failure(Failure e, HttpServletRequest req) { return error(e.status,e.code,e.getMessage(),req); }
        @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            jakarta.validation.ConstraintViolationException.class, org.springframework.web.bind.MissingRequestHeaderException.class})
        ResponseEntity<Envelope<Void>> bad(Exception e, HttpServletRequest req) { return error(400,"VALIDATION_ERROR","请求字段或参数不符合接口约定",req); }
        @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
        ResponseEntity<Envelope<Void>> duplicate(Exception e, HttpServletRequest req) { return error(409,"STATE_CONFLICT","数据冲突，请刷新后重试",req); }
        @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
        ResponseEntity<Envelope<Void>> tooLarge(Exception e,HttpServletRequest req){return error(413,"FILE_TOO_LARGE","图片须小于30MB，视频和附件须小于5GB",req);}
        @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
        ResponseEntity<Envelope<Void>> busy(Exception e, HttpServletRequest req) { return error(409,"STATE_CONFLICT","数据正在被其他操作更新，请刷新后重试",req); }
        @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
        ResponseEntity<Envelope<Void>> notFound(Exception e, HttpServletRequest req) { return error(404,"NOT_FOUND","接口不存在",req); }
        @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
        ResponseEntity<Envelope<Void>> method(Exception e, HttpServletRequest req) { return error(405,"METHOD_NOT_ALLOWED","请求方法不支持",req); }
        @ExceptionHandler(Exception.class)
        ResponseEntity<Envelope<Void>> unexpected(Exception e, HttpServletRequest req) {
            if(e instanceof org.springframework.web.ErrorResponse web) {
                int status=web.getStatusCode().value();
                return error(status,status==400?"VALIDATION_ERROR":"HTTP_"+status,"请求不符合接口约定",req);
            }
            log.error("Request failed: {}",req.getAttribute("requestId"),e);
            return error(500,"INTERNAL_ERROR","服务暂时不可用，请保留 requestId 联系管理员",req);
        }
        private ResponseEntity<Envelope<Void>> error(int status,String code,String message,HttpServletRequest req) {
            return ResponseEntity.status(status).body(new Envelope<>(code,message,null,(String)req.getAttribute("requestId")));
        }
    }
}
