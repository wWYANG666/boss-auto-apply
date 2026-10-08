package com.careerlens.core.automation;
import com.careerlens.core.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/platform-identities") @RequiredArgsConstructor
public class PlatformIdentityController {
 private final PlatformIdentityService identities;
 @GetMapping List<PlatformIdentityService.View> list(@AuthenticationPrincipal AppPrincipal user){return identities.list(user.id());}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) PlatformIdentityService.View create(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody Create request){return identities.create(user.id(),request.profileName(),request.displayName());}
 @PostMapping("/{id}/activate") PlatformIdentityService.View activate(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){return identities.activate(user.id(),id);}
 record Create(@NotBlank String profileName,@NotBlank String displayName){}
}
