package org.dromara.auth.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.constant.RegexConstants;
import org.dromara.common.core.domain.model.LoginBody;

@Data
@EqualsAndHashCode(callSuper = true)
public class ForgotPasswordBody extends LoginBody {

    @NotBlank(message = "{user.username.not.blank}")
    @Size(min = 2, max = 30, message = "{user.username.length.valid}")
    private String username;

    @NotBlank(message = "{user.password.not.blank}")
    @Size(min = 8, max = 30, message = "{user.password.length.valid}")
    @Pattern(regexp = RegexConstants.PASSWORD, message = "{user.password.format.valid}")
    private String newPassword;

    @NotBlank(message = "registered contact must not be blank")
    @Size(max = 50, message = "registered contact length must not exceed 50")
    private String contact;
}
