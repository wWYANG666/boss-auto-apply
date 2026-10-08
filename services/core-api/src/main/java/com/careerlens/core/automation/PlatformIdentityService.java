package com.careerlens.core.automation;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.PlatformIdentity;
import com.careerlens.core.integration.RunnerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class PlatformIdentityService {
    private final DataStore store;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final RunnerClient runner;

    @Transactional
    public List<View> list(UUID userId){ensureDefault(userId);return store.query("select i from PlatformIdentity i where i.userId=:uid and i.archivedAt is null order by i.active desc,i.createdAt",PlatformIdentity.class,Map.of("uid",userId)).stream().map(this::view).toList();}

    @Transactional
    public PlatformIdentity ensureDefault(UUID userId){
        return store.one("select i from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.profileName='default'",PlatformIdentity.class,Map.of("uid",userId)).orElseGet(()->{
            PlatformIdentity value=new PlatformIdentity();value.setUserId(userId);value.setPlatform("boss");value.setProfileName("default");value.setDisplayName("默认BOSS账号");value.setConnectionStatus("disconnected");
            long active=store.count("select count(i) from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.active=true",Map.of("uid",userId));value.setActive(active==0);store.persist(value);store.flush();
            jdbc.update("INSERT INTO platform_execution_policy(platform_identity_id,user_id,paused,daily_limit,dry_run) VALUES (?,?,FALSE,20,FALSE)",value.getId(),userId);return value;
        });
    }

    @Transactional(readOnly=true)
    public PlatformIdentity active(UUID userId){return store.one("select i from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.active=true and i.archivedAt is null",PlatformIdentity.class,Map.of("uid",userId)).orElseThrow(()->ApiException.conflict("NO_ACTIVE_BOSS_ACCOUNT","请先选择BOSS账号"));}
    @Transactional(readOnly=true) public UUID activeId(UUID userId){return active(userId).getId();}
    @Transactional(readOnly=true) public UUID activeIdOrNull(UUID userId){return jdbc.query("SELECT id FROM platform_identity WHERE user_id=? AND platform='boss' AND active=TRUE AND archived_at IS NULL",(rs,n)->rs.getObject(1,UUID.class),userId).stream().findFirst().orElse(null);}
    @Transactional(readOnly=true) public PlatformIdentity owned(UUID userId,UUID id){return store.one("select i from PlatformIdentity i where i.id=:id and i.userId=:uid and i.archivedAt is null",PlatformIdentity.class,Map.of("id",id,"uid",userId)).orElseThrow(()->ApiException.notFound("BOSS账号"));}

    @Transactional
    public View create(UUID userId,String profileName,String displayName){
        String profile=Objects.toString(profileName,"").trim();if(!profile.matches("[a-zA-Z0-9_-]{1,32}"))throw ApiException.badRequest("BOSS_PROFILE_INVALID","配置名称只允许字母、数字、下划线和短横线");
        if(store.count("select count(i) from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.profileName=:profile",Map.of("uid",userId,"profile",profile))>0)throw ApiException.conflict("BOSS_PROFILE_EXISTS","该BOSS配置已经存在");
        PlatformIdentity value=new PlatformIdentity();value.setUserId(userId);value.setPlatform("boss");value.setProfileName(profile);value.setDisplayName(Objects.toString(displayName,profile).trim());value.setConnectionStatus("disconnected");value.setActive(false);store.persist(value);store.flush();
        jdbc.update("INSERT INTO platform_execution_policy(platform_identity_id,user_id,paused,daily_limit,dry_run) VALUES (?,?,FALSE,20,FALSE)",value.getId(),userId);return view(value);
    }

    @Transactional
    public View activate(UUID userId,UUID id){
        PlatformIdentity target=owned(userId,id);requireSwitchAllowed(userId);
        Map<String,Object> state=runner.activateBossProfile(target.getProfileName());
        store.query("select i from PlatformIdentity i where i.userId=:uid and i.platform='boss'",PlatformIdentity.class,Map.of("uid",userId)).forEach(identity->identity.setActive(identity.getId().equals(id)));
        target.setConnectionStatus(Boolean.TRUE.equals(state.get("connected"))?"connected":"needs_auth");target.setLastCheckedAt(Instant.now());
        jdbc.update("UPDATE platform_account SET connection_status=?,last_checked_at=CURRENT_TIMESTAMP WHERE user_id=? AND platform='boss'",target.getConnectionStatus(),userId);return view(target);
    }

    @Transactional
    public void syncConnection(UUID userId,Map<String,Object> state){
        String profile=Objects.toString(state.getOrDefault("profileName","default"));
        PlatformIdentity identity=store.one("select i from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.profileName=:profile",PlatformIdentity.class,Map.of("uid",userId,"profile",profile)).orElseGet(()->ensureDefault(userId));
        String fingerprint=Objects.toString(state.get("accountFingerprint"),"");
        if(!fingerprint.isBlank()&&store.count("select count(i) from PlatformIdentity i where i.userId=:uid and i.platform='boss' and i.accountFingerprint=:fingerprint and i.id<>:id",Map.of("uid",userId,"fingerprint",fingerprint,"id",identity.getId()))>0)
            throw ApiException.conflict("BOSS_ACCOUNT_ALREADY_CONFIGURED","该BOSS登录账号已绑定其他配置");
        if(!fingerprint.isBlank())identity.setAccountFingerprint(fingerprint);
        identity.setMaskedIdentity(Objects.toString(state.getOrDefault("maskedIdentity",identity.getDisplayName())));identity.setConnectionStatus(Boolean.TRUE.equals(state.get("connected"))?"connected":"needs_auth");identity.setLastCheckedAt(Instant.now());
    }
    @Transactional public void disconnectActive(UUID userId){PlatformIdentity identity=active(userId);identity.setConnectionStatus("disconnected");identity.setLastCheckedAt(Instant.now());}

    private void requireSwitchAllowed(UUID userId){
        Long tasks=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND task_status IN ('queued','preparing','submitting','verifying','awaiting_login','awaiting_captcha','awaiting_question','unknown_outcome')",Long.class,userId);
        Long runs=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_run WHERE user_id=? AND status IN ('running','paused')",Long.class,userId);
        if((tasks!=null&&tasks>0)||(runs!=null&&runs>0))throw ApiException.conflict("BOSS_ACCOUNT_SWITCH_BLOCKED","存在活动任务或一条龙，请先处理或停止后再切换账号");
    }
    private View view(PlatformIdentity value){return new View(value.getId(),value.getPlatform(),value.getProfileName(),value.getDisplayName(),value.getMaskedIdentity(),value.getConnectionStatus(),value.isActive(),value.getLastCheckedAt());}
    public record View(UUID id,String platform,String profileName,String displayName,String maskedIdentity,String status,boolean active,Instant lastCheckedAt){}
}
