package org.dromara.auth.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.constant.RegexConstants;
import org.dromara.common.core.domain.model.LoginBody;
import org.hibernate.validator.constraints.Length;

/**
 * 用户注册对象
 *
 * @author Lion Li
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RegisterBody extends LoginBody {

    /**
     * 用户名
     */
    @NotBlank(message = "{user.username.not.blank}")
    @Length(min = 2, max = 30, message = "{user.username.length.valid}")
    private String username;

    /**
     * 用户密码
     */
    @NotBlank(message = "{user.password.not.blank}")
    @Length(min = 8, max = 30, message = "{user.password.length.valid}")
    @Pattern(regexp = RegexConstants.PASSWORD, message = "{user.password.format.valid}")
    private String password;

    @Email(message = "{user.email.not.valid}")
    @Size(max = 50, message = "{user.email.length.valid}")
    private String email;

    @Size(max = 11, message = "{user.phonenumber.length.valid}")
    private String phonenumber;

    /**
     * 用户类型
     */
    private String userType;

}
