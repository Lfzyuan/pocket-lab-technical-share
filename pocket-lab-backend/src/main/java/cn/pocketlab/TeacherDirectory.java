package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static cn.pocketlab.Business.Input;

@RestController
public class TeacherDirectory {
    private static final String ACTIVE_TEACHER =
        "enabled=TRUE AND deleted_at IS NULL AND account_type='teacher' AND role<>'admin'";
    private static final String DEPARTMENT =
        "COALESCE(NULLIF(TRIM(class_name),''),TRIM(grade),'')";
    private static final String FIELDS = "id,name,account," + DEPARTMENT + " AS department";

    record Teacher(String id, String name, String account, String department) {}
    record Selection(String id, String name) {}
    private static final Selection NONE = new Selection(null, "");
    private final Store store;
    private final Business business;

    TeacherDirectory(Store store, Business business) {
        this.store = store;
        this.business = business;
    }

    @GetMapping("/api/users/teachers")
    public Object search(@AuthenticationPrincipal Api.User actor,
                         @RequestParam(defaultValue="") String keyword,
                         @RequestParam(defaultValue="1") int page,
                         @RequestParam(defaultValue="20") int pageSize,
                         @RequestParam(defaultValue="false") boolean exact,
                         HttpServletRequest request) {
        Store.role(actor, "student", "reviewer", "admin");
        keyword = keyword.trim();
        if (keyword.length() > 100) throw Api.invalid("教师搜索关键词过长");
        String where = " WHERE " + ACTIVE_TEACHER;
        List<Object> parameters = List.of();
        if (exact) {
            if (keyword.isEmpty()) throw Api.invalid("请输入教师姓名或工号");
            where += " AND (name=? OR account=?)";
            parameters = List.of(keyword, keyword);
        } else if (!keyword.isEmpty()) {
            String match = Business.like(keyword);
            where += " AND (name LIKE ? ESCAPE '!' OR account LIKE ? ESCAPE '!' OR "
                + DEPARTMENT + " LIKE ? ESCAPE '!')";
            parameters = List.of(match, match, match);
        }
        return Api.ok(business.page("SELECT " + FIELDS, "FROM lab_user", where,
            parameters, "name,account,id", page, pageSize), request);
    }

    Teacher require(String id) {
        return store.db.query("SELECT " + FIELDS + " FROM lab_user WHERE id=? AND " + ACTIVE_TEACHER,
            (row, n) -> new Teacher(row.getString("id"), row.getString("name"),
                row.getString("account"), row.getString("department")), id)
            .stream().findFirst().orElseThrow(() -> Api.invalid("请选择仍启用的教师账号"));
    }

    Selection optional(Input input, Map<String, Object> previous) {
        if (input.node.has("teacherUserId")) {
            String id = input.text("teacherUserId", 36, false);
            if (id.isEmpty()) {
                if (!input.text("teacherName", 80, false).isEmpty()) throw Api.invalid("请从教师账号中选择指导老师");
                return NONE;
            }
            Teacher teacher = require(id);
            return new Selection(teacher.id(), teacher.name());
        }
        String previousName = previous == null ? "" : Objects.toString(previous.get("teacherName"), "");
        if (input.has("teacherName") && !input.text("teacherName", 80, false).equals(previousName)) {
            throw Api.invalid("请从教师账号中选择指导老师");
        }
        if (previous == null) return NONE;
        return new Selection((String) previous.get("teacherUserId"), previousName);
    }
}
