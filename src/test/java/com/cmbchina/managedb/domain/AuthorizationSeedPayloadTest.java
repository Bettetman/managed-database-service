package com.cmbchina.managedb.domain;

import com.cmbchina.managedb.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.cmbchina.managedb.AuthorizationSeedFixture.KEY;
import static com.cmbchina.managedb.AuthorizationSeedFixture.json;
import static com.cmbchina.managedb.AuthorizationSeedFixture.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通过现有请求解析入口验证资源引用和描述字段。 */
class AuthorizationSeedPayloadTest {
    private static final String ACTION_REF = "action:home:edit";

    /** 操作引用及空描述原样进入载荷，描述变化也会改变请求指纹。 */
    @Test
    void acceptsActionAndPreservesEmptyDescription() {
        AuthorizationSeedPayload empty = parse(KEY, actionRequest(ACTION_REF, "\"\""));
        AuthorizationSeedPayload described = parse(KEY, actionRequest(ACTION_REF, "\"编辑首页\""));
        Map<String, Object> action = empty.getRows().get(1).get(2);
        assertEquals(ACTION_REF, action.get("target_resource_ref"));
        assertEquals("", action.get("description"));
        assertNotEquals(empty.getFingerprint(), described.getFingerprint());
    }

    /** 拒绝旧前缀、缺段、多段及非法标识符。 */
    @ParameterizedTest
    @ValueSource(strings = {"operation:home:edit", "action:home", "action::edit", "action:home:",
            "action:home:edit:extra", "action:-home:edit", "action:Home:edit", "action:home:1edit"})
    void rejectsInvalidActionReference(String ref) {
        reject(actionRequest(ref, "\"\""));
    }

    /** 页面引用必须和本行资源键一致。 */
    @Test
    void rejectsPageReferenceMismatch() {
        reject(json(KEY).replace("page:home", "page:other"));
    }

    /** 空描述例外只接受空串，其他非法内容继续拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"null", "\"   \"", "\"　\"", "\" 描述 \"", "\"\\u0001\""})
    void rejectsInvalidResourceDescription(String descriptionJson) {
        reject(actionRequest(ACTION_REF, descriptionJson));
    }

    /** 角色描述与其他必填文本仍不能用空串替代。 */
    @Test
    void rejectsEmptyRequiredTextOutsideResourceDescription() {
        String request = actionRequest(ACTION_REF, "\"\"");
        reject(request.replace("\"description\":\"系统角色\"", "\"description\":\"\""));
        reject(request.replace("\"name\":\"编辑首页\"", "\"name\":\"\""));
    }

    /** 在现有四表测试请求中追加一条操作资源。 */
    private static String actionRequest(String ref, String descriptionJson) {
        return json(KEY).replace("\"target_resource_ref\":\"page:home\"}]",
                "\"target_resource_ref\":\"page:home\"},{\"key\":\"home_edit\",\"name\":\"编辑首页\","
                        + "\"description\":" + descriptionJson + ",\"origin\":\"business\",\"type\":\"operation\","
                        + "\"target_resource_ref\":\"" + ref + "\"}]");
    }

    /** 拒绝非法请求时仍使用原有错误码。 */
    private static void reject(String request) {
        BizException error = assertThrows(BizException.class, () -> parse(KEY, request));
        assertEquals(ResourceErrorCode.INVALID_ARGUMENT, error.getErrorCode());
    }
}
