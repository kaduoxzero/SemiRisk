package com.semirisk.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class InputSanitizer {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final Pattern QQ_EMAIL = Pattern.compile("^[1-9][0-9]{4,11}@qq\\.com$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");

    public String username(String value) {
        String clean = plain(value, 32);
        if (!USERNAME.matcher(clean).matches()) {
            throw new IllegalArgumentException("账号仅支持 3-32 位字母、数字或下划线");
        }
        return clean;
    }

    public String qqEmail(String value) {
        String clean = plain(value, 255).toLowerCase(Locale.ROOT);
        if (!QQ_EMAIL.matcher(clean).matches()) {
            throw new IllegalArgumentException("注册邮箱必须为 QQ 邮箱，例如 123456@qq.com");
        }
        return clean;
    }

    public String displayName(String value) {
        String clean = plain(value, 40);
        if (clean.length() < 2) {
            throw new IllegalArgumentException("姓名/昵称至少 2 个字符");
        }
        return clean;
    }

    public String password(String value) {
        if (value == null || value.length() < 8 || value.length() > 72) {
            throw new IllegalArgumentException("密码长度必须为 8-72 位");
        }
        if (CONTROL.matcher(value).find() || value.contains("<") || value.contains(">")) {
            throw new IllegalArgumentException("密码包含非法字符");
        }
        return value;
    }

    public String loginPassword(String value) {
        if (value == null || value.isBlank() || value.length() > 72) {
            throw new IllegalArgumentException("密码不合法");
        }
        if (CONTROL.matcher(value).find() || value.contains("<") || value.contains(">")) {
            throw new IllegalArgumentException("密码包含非法字符");
        }
        return value;
    }

    public String role(String value) {
        String clean = plain(value, 32);
        if ("普通用户".equals(clean)) {
            return "OPERATOR";
        }
        return switch (clean) {
            case "ADMIN", "管理员" -> "ADMIN";
            case "ANALYST", "分析师" -> "ANALYST";
            case "OPERATOR", "运营人员" -> "OPERATOR";
            default -> throw new IllegalArgumentException("角色不合法");
        };
    }

    public String status(String value) {
        String clean = plain(value, 16);
        if (!"启用".equals(clean) && !"禁用".equals(clean) && !"已忽略".equals(clean) && !"已处理".equals(clean) && !"处理中".equals(clean) && !"未处理".equals(clean)) {
            throw new IllegalArgumentException("状态不合法");
        }
        return clean;
    }

    public String plain(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String clean = CONTROL.matcher(TAG.matcher(value).replaceAll("")).replaceAll("").trim();
        if (clean.length() > maxLength) {
            return clean.substring(0, maxLength);
        }
        return clean;
    }
}
