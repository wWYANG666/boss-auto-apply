package com.careerlens.core.application;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.ApplicationEvent;
import com.careerlens.core.domain.Entities.JobApplication;
import com.careerlens.core.automation.PlatformIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ApplicationService {
    private static final Set<String> STAGES = Set.of("wishlist", "applied", "test", "interview", "offer", "contacted", "rejected", "withdrawn", "closed", "hired");
    private final DataStore store;
    private final Jsons jsons;
    private final JdbcTemplate jdbc;
    private final PlatformIdentityService identities;

    @Transactional(readOnly = true)
    public List<ApplicationView> list(UUID userId, String stage, String query) {
        return list(userId,stage,query,false);
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> list(UUID userId,String stage,String query,boolean allAccounts) {
        UUID identity=identities.activeIdOrNull(userId);
        Map<String,Object> listParameters=new HashMap<>();listParameters.put("uid",userId);if(!allAccounts)listParameters.put("identity",identity);
        List<JobApplication> applications = store.query(
                "select a from JobApplication a where a.userId=:uid"+(allAccounts?"":" and (a.platform is null or a.platform<>'boss' or a.platformIdentityId=:identity)")+" order by a.updatedAt desc",
                JobApplication.class,listParameters);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return applications.stream()
                .filter(item -> stage == null || stage.isBlank() || item.getStage().equals(stage))
                .filter(item -> needle.isBlank() || (item.getCompany() + " " + item.getRoleName() + " "
                        + Objects.toString(item.getLocation(), "")).toLowerCase(Locale.ROOT).contains(needle))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDetail get(UUID userId, UUID id) {
        JobApplication application = owned(userId, id);
        List<EventView> events = store.query(
                "select e from ApplicationEvent e where e.userId=:uid and e.applicationId=:aid order by e.createdAt desc",
                ApplicationEvent.class, Map.of("uid", userId, "aid", id)).stream().map(this::eventView).toList();
        return new ApplicationDetail(view(application), events);
    }

    @Transactional
    public ApplicationView create(UUID userId, CreateCommand command) {
        return create(userId,"boss".equals(command.platform())?identities.activeIdOrNull(userId):null,command);
    }

    @Transactional
    public ApplicationView create(UUID userId,UUID platformIdentityId,CreateCommand command) {
        JobApplication application = new JobApplication();
        application.setUserId(userId);
        application.setPlatformIdentityId(platformIdentityId);
        application.setCompany(command.company().trim());
        application.setRoleName(command.role().trim());
        application.setLocation(command.location());
        application.setStage(normalizeStage(command.stage() == null ? "wishlist" : command.stage()));
        application.setMatchScore(command.matchScore() == null ? 0 : Math.max(0, Math.min(100, command.matchScore())));
        application.setNextAction(command.nextAction());
        application.setLogoText(command.logoText() == null || command.logoText().isBlank()
                ? command.company().substring(0, Math.min(1, command.company().length())) : command.logoText());
        application.setLogoTone(command.logoTone() == null ? "#eef4f0" : command.logoTone());
        application.setTagsText(jsons.write(command.tags() == null ? List.of("待分析") : command.tags()));
        application.setPlatform(command.platform());
        application.setExternalJobId(command.externalJobId());
        application.setResumeVersionNumber(command.resumeVersion());
        application.setActionType(command.actionType() == null ? "MANUAL" : command.actionType());
        application.setAutomationStatus(command.automationStatus());
        application.setReceipt(command.receipt());
        store.persist(application);
        store.flush();
        addEvent(userId, application, "CREATED", null, application.getStage(), "岗位已加入投递管理");
        return view(application);
    }

    @Transactional
    public ApplicationView update(UUID userId, UUID id, UpdateCommand command) {
        JobApplication application = owned(userId, id);
        String before = application.getStage();
        if (command.stage() != null) application.setStage(normalizeStage(command.stage()));
        if (command.nextActionSet()) application.setNextAction(command.nextAction());
        if (command.tags() != null) application.setTagsText(jsons.write(command.tags()));
        if (command.automationStatus() != null) application.setAutomationStatus(command.automationStatus());
        if (command.receipt() != null) application.setReceipt(command.receipt());
        if (!before.equals(application.getStage())) {
            addEvent(userId, application, "STAGE_CHANGED", before, application.getStage(), "投递阶段已更新");
        }
        return view(application);
    }

    @Transactional
    public EventView addEvent(UUID userId, UUID id, String eventType, String detail, String toStage) {
        JobApplication application = owned(userId, id);
        String before = application.getStage();
        if (toStage != null) application.setStage(normalizeStage(toStage));
        return eventView(addEvent(userId, application, eventType == null ? "NOTE" : eventType,
                before, application.getStage(), detail == null ? "" : detail));
    }

    @Transactional(readOnly = true)
    public Stats stats(UUID userId) {
        List<ApplicationView> all = list(userId, null, null);
        Map<String, Long> byStage = new LinkedHashMap<>();
        STAGES.forEach(stage -> byStage.put(stage, all.stream().filter(item -> stage.equals(item.stage())).count()));
        long automated = all.stream().filter(item -> item.automationStatus() != null).count();
        long needsAction = all.stream().filter(item -> item.automationStatus() != null
                && item.automationStatus().startsWith("awaiting_")).count();
        return new Stats(all.size(), byStage, automated, needsAction);
    }

    @Transactional(readOnly = true)
    public DailyStats dailyStats(UUID userId, int requestedDays) {
        int days=Set.of(7,14,30).contains(requestedDays)?requestedDays:7;
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDate today=LocalDate.now(zone),first=today.minusDays(days-1L);
        Instant from=first.atStartOfDay(zone).toInstant();
        UUID identity=identities.activeIdOrNull(userId);
        Map<String,Object> parameters=new HashMap<>();parameters.put("uid",userId);parameters.put("from",from);
        if(identity!=null)parameters.put("identity",identity);
        List<JobApplication> applications=store.query(
                "select a from JobApplication a where a.userId=:uid and a.createdAt>=:from and (a.platform is null or a.platform<>'boss'"+(identity==null?"": " or a.platformIdentityId=:identity")+")",
                JobApplication.class,parameters);
        Map<LocalDate,int[]> counts=new LinkedHashMap<>();
        for(int offset=0;offset<days;offset++)counts.put(first.plusDays(offset),new int[2]);
        for(JobApplication application:applications){
            if("wishlist".equals(application.getStage()))continue;
            LocalDate date=application.getCreatedAt().atZone(zone).toLocalDate();
            int[] value=counts.get(date);if(value==null)continue;
            boolean contacted="CHAT".equals(application.getActionType())||"contacted".equals(application.getStage());
            value[contacted?0:1]++;
        }
        List<DailyItem> items=counts.entrySet().stream().map(entry->new DailyItem(entry.getKey().toString(),entry.getValue()[0],entry.getValue()[1],entry.getValue()[0]+entry.getValue()[1])).toList();
        int total=items.stream().mapToInt(DailyItem::total).sum();
        int todayTotal=items.isEmpty()?0:items.get(items.size()-1).total();
        return new DailyStats(days,todayTotal,total,Math.round(total*10.0/days)/10.0,items);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        JobApplication application = owned(userId, id);
        if (!isDeletable(application)) {
            throw ApiException.conflict("APPLICATION_DELETE_LOCKED", "已投递或已沟通记录不可删除，以避免重复投递。只能删除尚未投递的待投递记录。");
        }
        jdbc.update("DELETE FROM application_followup WHERE user_id=? AND application_id=?", userId, id);
        store.update("delete from ApplicationEvent e where e.userId=:uid and e.applicationId=:aid",
                Map.of("uid", userId, "aid", id));
        store.remove(application);
    }

    private boolean isDeletable(JobApplication application) {
        return "wishlist".equals(application.getStage()) && !"succeeded".equals(application.getAutomationStatus());
    }

    public JobApplication owned(UUID userId, UUID id) {
        JobApplication value=store.one("select a from JobApplication a where a.id=:id and a.userId=:uid", JobApplication.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("投递记录"));
        if("boss".equals(value.getPlatform())&&!Objects.equals(value.getPlatformIdentityId(),identities.activeIdOrNull(userId)))throw ApiException.notFound("投递记录");
        return value;
    }

    private ApplicationEvent addEvent(UUID userId, JobApplication application, String type, String from, String to, String detail) {
        ApplicationEvent event = new ApplicationEvent();
        event.setUserId(userId);
        event.setApplicationId(application.getId());
        event.setEventType(type);
        event.setFromStage(from);
        event.setToStage(to);
        event.setDetailText(detail);
        store.persist(event);
        return event;
    }

    private String normalizeStage(String stage) {
        String normalized = stage.toLowerCase(Locale.ROOT);
        if (!STAGES.contains(normalized)) throw ApiException.badRequest("INVALID_APPLICATION_STAGE", "不支持的投递阶段");
        return normalized;
    }

    private ApplicationView view(JobApplication application) {
        return new ApplicationView(application.getId(), application.getCompany(), application.getRoleName(),
                application.getLocation(), application.getStage(), application.getMatchScore(), application.getUpdatedAt(),
                application.getNextAction(), application.getLogoText(), application.getLogoTone(), strings(application.getTagsText()),
                application.getPlatform(), application.getExternalJobId(), null, application.getResumeVersionNumber(),
                application.getActionType(), application.getAutomationStatus(), application.getReceipt(),application.getPlatformIdentityId(),application.getHandoffStatus(),application.getHandedOffAt());
    }

    private EventView eventView(ApplicationEvent event) {
        return new EventView(event.getId(), event.getEventType(), event.getFromStage(), event.getToStage(),
                event.getDetailText(), event.getCreatedAt());
    }
    private List<String> strings(String text) { return jsons.list(text).stream().map(String::valueOf).toList(); }

    public record CreateCommand(String company, String role, String location, String stage, Integer matchScore,
                                String nextAction, String logoText, String logoTone, List<String> tags, String platform,
                                String externalJobId, Integer resumeVersion, String actionType,
                                String automationStatus, String receipt) {}
    public record UpdateCommand(String stage, String nextAction, boolean nextActionSet, List<String> tags,
                                String automationStatus, String receipt) {}
    public record ApplicationView(UUID id, String company, String role, String location, String stage, int matchScore,
                                  Instant updatedAt, String nextAction, String logoText, String logoTone, List<String> tags,
                                  String platform, String externalJobId, UUID resumeVersionId, Integer resumeVersion,
                                  String actionType, String automationStatus, String receipt,UUID platformIdentityId,String handoffStatus,Instant handedOffAt) {}
    public record EventView(UUID id, String type, String fromStage, String toStage, String detail, Instant occurredAt) {}
    public record ApplicationDetail(ApplicationView application, List<EventView> events) {}
    public record Stats(int total, Map<String, Long> byStage, long automated, long needsHumanAction) {}
    public record DailyItem(String date,int contacted,int submitted,int total) {}
    public record DailyStats(int days,int today,int total,double average,List<DailyItem> items) {}
}
