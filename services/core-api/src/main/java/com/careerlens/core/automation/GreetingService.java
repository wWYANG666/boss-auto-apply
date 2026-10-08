package com.careerlens.core.automation;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.DiscoveredJob;
import com.careerlens.core.domain.Entities.ResumeVersion;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class GreetingService {
    private static final List<String> SKILLS=List.of("Java","Spring Boot","Spring Cloud","MySQL","Redis","RabbitMQ","Kafka","WebSocket","Docker","React","TypeScript","Vue","Python","MyBatis","JWT","Electron");
    private static final Pattern PRIVATE=Pattern.compile("(?i)(1[3-9]\\d{9}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|https?://|微信|手机号)");
    private final JdbcTemplate jdbc;
    private final DataStore store;
    private final Jsons jsons;
    private final PlatformIdentityService identities;

    @Transactional
    public PolicyView policy(UUID userId){
        UUID identity=identities.activeId(userId);
        List<PolicyView> rows=jdbc.query("SELECT active_resume_version_id,default_style,include_question,max_length,banned_words_text,preferred_words_text,ai_enabled FROM greeting_policy WHERE user_id=? AND platform_identity_id=?",
                (rs,n)->new PolicyView(rs.getObject(1,UUID.class),rs.getString(2),rs.getBoolean(3),rs.getInt(4),strings(rs.getString(5)),strings(rs.getString(6)),rs.getBoolean(7),availableResumes(userId)),userId,identity);
        if(!rows.isEmpty()){
            PolicyView current=rows.get(0);
            if(current.activeResumeVersionId()!=null){
                List<UUID> latest=jdbc.query("SELECT r.current_version_id FROM resume_version v JOIN resume r ON r.id=v.resume_id WHERE v.id=? AND v.user_id=? AND r.current_version_id IS NOT NULL",(rs,n)->rs.getObject(1,UUID.class),current.activeResumeVersionId(),userId);
                if(!latest.isEmpty()&&!latest.get(0).equals(current.activeResumeVersionId())){jdbc.update("UPDATE greeting_policy SET active_resume_version_id=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND platform_identity_id=?",latest.get(0),userId,identity);return policy(userId);}
            }
            return current;
        }
        UUID selected=bestResumeVersion(userId);
        jdbc.update("INSERT INTO greeting_policy(id,user_id,platform_identity_id,active_resume_version_id) VALUES (?,?,?,?)",UUID.randomUUID(),userId,identity,selected);
        return policy(userId);
    }

    @Transactional
    public PolicyView update(UUID userId,PolicyUpdate input){
        UUID identity=identities.activeId(userId);
        if(input.activeResumeVersionId()!=null)requireUsableResume(userId,input.activeResumeVersionId());
        int max=Math.max(50,Math.min(100,input.maxLength()));
        List<String> banned=clean(input.bannedWords(),20),preferred=clean(input.preferredWords(),20);
        if(jdbc.update("UPDATE greeting_policy SET active_resume_version_id=?,default_style=?,include_question=?,max_length=?,banned_words_text=?,preferred_words_text=?,ai_enabled=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND platform_identity_id=?",
                input.activeResumeVersionId(),style(input.defaultStyle()),input.includeQuestion(),max,jsons.write(banned),jsons.write(preferred),input.aiEnabled(),userId,identity)==0){
            jdbc.update("INSERT INTO greeting_policy(id,user_id,platform_identity_id,active_resume_version_id,default_style,include_question,max_length,banned_words_text,preferred_words_text,ai_enabled) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),userId,identity,input.activeResumeVersionId(),style(input.defaultStyle()),input.includeQuestion(),max,jsons.write(banned),jsons.write(preferred),input.aiEnabled());
        }
        return policy(userId);
    }

    public UUID resolveResumeVersion(UUID userId,UUID requested){
        UUID configured=policy(userId).activeResumeVersionId();
        UUID selected=configured!=null?configured:requested;
        if(selected==null)throw ApiException.badRequest("GREETING_RESUME_REQUIRED","请先在设置页选择用于招呼语的已发布简历");
        requireUsableResume(userId,selected);return selected;
    }

    public Generated generate(UUID userId,DiscoveredJob job,UUID resumeVersionId){
        PolicyView policy=policy(userId);ResumeVersion version=requireUsableResume(userId,resumeVersionId);
        String resume=Objects.toString(version.getContentText(),"");String jobText=(job.getRoleName()+" "+job.getSummaryText()+" "+job.getJobContentText()).toLowerCase(Locale.ROOT);
        List<String> jobSkills=SKILLS.stream().filter(skill->containsIgnoreCase(jobText,skill)).toList();
        ProjectFact project=selectProject(resume,jobText,jobSkills);
        List<String> matched=jobSkills.stream().filter(project.skills()::contains).limit(2).toList();
        if(matched.isEmpty())matched=project.skills().stream().limit(2).toList();
        String skills=String.join("、",matched);String role=normalizeRole(job.getRoleName(),jobText);String question=policy.includeQuestion()?question(jobText):"";
        String factPhrase=factPhrase(project.fact());boolean campus=isCampus(jobText);
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("resumeVersionId",version.getId());evidence.put("project",project.title());evidence.put("projectLabel",project.label());evidence.put("fact",project.fact());evidence.put("factPhrase",factPhrase);evidence.put("skills",matched);evidence.put("roleFamily",roleFamily(jobText));
        String opening=opening(job.getExternalJobId(),role);
        String projectPrimary=normalizeGreeting(opening+"我独立完成过"+project.label()+"，"+factPhrase+"。"+question);
        String projectFallback=normalizeGreeting(opening+"我做过"+project.label()+"，有"+nonBlank(skills,"相关")+"实践。"+question);
        String skillPrimary=normalizeGreeting((skills.isBlank()?opening:"您好，看到岗位需要"+skills+"。")+"我在"+project.label()+"中实际使用过相关技术，"+factPhrase+"。"+question);
        String skillFallback=normalizeGreeting(opening+"我有"+nonBlank(skills,"软件开发")+"项目实践，"+factPhrase+"。"+question);
        String shortPrimary=normalizeGreeting(opening+(campus?"我是软件工程应届生，":"")+"有"+nonBlank(skills,"软件开发")+"项目实践。"+question);
        List<String> recent=jdbc.query("SELECT greeting_text FROM application_plan WHERE user_id=? AND platform_identity_id=? AND greeting_text IS NOT NULL ORDER BY updated_at DESC LIMIT 30",(rs,n)->rs.getString(1),userId,identities.activeId(userId));
        List<Candidate> candidates=List.of(
                candidate("PROJECT_EVIDENCE",chooseLength(projectPrimary,projectFallback,role,policy.maxLength()),evidence,policy,recent,role),
                candidate("SKILL_MATCH",chooseLength(skillPrimary,skillFallback,role,policy.maxLength()),evidence,policy,recent,role),
                candidate("SHORT_QUESTION",chooseLength(shortPrimary,"您好，看到您在招"+role+"。我有相关项目实践，请问岗位目前还在招聘吗？",role,policy.maxLength()),evidence,policy,recent,role)
        );
        Candidate selected=candidates.stream().filter(item->item.style().equals(style(policy.defaultStyle()))&&item.valid()&&item.qualityScore()>=70).findFirst().orElseGet(()->candidates.stream().filter(Candidate::valid).max(Comparator.comparingInt(Candidate::qualityScore)).orElseThrow(()->ApiException.badRequest("GREETING_GENERATION_FAILED","没有通过事实校验的招呼语")));
        return new Generated(selected.text(),selected.style(),evidence,candidates);
    }

    public void validateSelected(String text,Map<String,Object> evidence,PolicyView policy){
        List<String> warnings=warnings(text,evidence,policy);if(!warnings.isEmpty())throw ApiException.badRequest("INVALID_BOSS_GREETING",String.join("；",warnings));
    }

    private Candidate candidate(String style,String text,Map<String,Object> evidence,PolicyView policy,List<String> recent,String role){List<String> warnings=warnings(text,evidence,policy);int quality=qualityScore(text,evidence,recent,role);return new Candidate(style,text,evidence,warnings,warnings.isEmpty(),quality);}
    private List<String> warnings(String text,Map<String,Object> evidence,PolicyView policy){
        String value=Objects.toString(text,"");List<String> result=new ArrayList<>();if(value.isBlank()||value.length()>Math.min(100,policy.maxLength())||value.length()<30)result.add("招呼语长度不符合要求");if(PRIVATE.matcher(value).find())result.add("招呼语包含联系方式或链接");for(String banned:policy.bannedWords())if(!banned.isBlank()&&value.contains(banned))result.add("包含禁用词“"+banned+"”");if(Objects.toString(evidence.get("project"),"").isBlank())result.add("缺少简历项目证据");if(value.matches(".*[。！？，；]{2,}.*"))result.add("存在重复标点");if(value.contains("实现基于")||value.matches(".*实现.{0,12}实现.*"))result.add("存在重复或不自然表达");if(!balanced(value,'（','）')||!balanced(value,'(',')'))result.add("括号不完整");if(value.chars().filter(ch->ch=='？'||ch=='?').count()>1)result.add("问题数量过多");if(value.matches(".*(双休|五险一金|高薪|急招|全额社保).*"))result.add("包含岗位营销词");
        @SuppressWarnings("unchecked") List<String> skills=evidence.get("skills") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();String label=Objects.toString(evidence.get("projectLabel"),"");if(skills.stream().noneMatch(value::contains)&&(!label.isBlank()&&!value.contains(label)))result.add("正文未引用简历证据");return result;
    }
    private ResumeVersion requireUsableResume(UUID userId,UUID id){ResumeVersion version=store.one("select v from ResumeVersion v where v.id=:id and v.userId=:uid",ResumeVersion.class,Map.of("id",id,"uid",userId)).orElseThrow(()->ApiException.notFound("简历版本"));String content=Objects.toString(version.getContentText(),"");if(content.length()<300||!content.contains("PROJECT"))throw ApiException.badRequest("GREETING_RESUME_EMPTY","所选简历缺少可用于招呼语的项目内容");return version;}
    private UUID bestResumeVersion(UUID userId){return jdbc.query("SELECT v.id FROM resume r JOIN resume_version v ON v.id=r.current_version_id WHERE r.user_id=? AND LENGTH(v.content_text)>300 AND v.content_text LIKE '%PROJECT%' ORDER BY r.updated_at DESC",(rs,n)->rs.getObject(1,UUID.class),userId).stream().findFirst().orElse(null);}
    private List<ResumeOption> availableResumes(UUID userId){return jdbc.query("SELECT v.id,r.title,v.version_number,LENGTH(v.content_text) FROM resume r JOIN resume_version v ON v.id=r.current_version_id WHERE r.user_id=? ORDER BY r.updated_at DESC",(rs,n)->new ResumeOption(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getInt(4)>300&&rs.getString(2)!=null),userId);}
    private ProjectFact selectProject(String resume,String jobText,List<String> jobSkills){
        JsonNode root=jsons.tree(resume);List<ProjectFact> projects=new ArrayList<>();
        for(JsonNode section:root.path("sections")){
            if(!"PROJECT".equalsIgnoreCase(section.path("type").asText()))continue;
            if(section.path("items").isArray()){
                for(JsonNode item:section.path("items"))addProject(projects,item,jobText,jobSkills);
            }else{
                // Legacy resumes store each project directly on its section.
                addProject(projects,section,jobText,jobSkills);
            }
        }
        return projects.stream().max(Comparator.comparingInt(ProjectFact::score)).orElseThrow(()->ApiException.badRequest("GREETING_PROJECT_REQUIRED","所选简历缺少可用于招呼语的结构化项目内容"));
    }
    private void addProject(List<ProjectFact> projects,JsonNode item,String jobText,List<String> jobSkills){
        String title=item.path("title").asText("");
        if(title.isBlank())return;
        StringBuilder text=new StringBuilder(title).append(' ').append(item.path("description").asText(""));
        for(JsonNode highlight:item.path("highlights"))text.append(' ').append(highlight.asText(""));
        List<String> skills=SKILLS.stream().filter(skill->containsIgnoreCase(text.toString(),skill)).toList();
        projects.add(new ProjectFact(title,projectLabel(title,text.toString()),projectFact(text.toString(),jobText),skills,score(text.toString(),jobText,jobSkills)));
    }
    private int score(String project,String job,List<String> skills){String lower=project.toLowerCase(Locale.ROOT);int score=0;for(String skill:skills)if(lower.contains(skill.toLowerCase(Locale.ROOT)))score+=4;if((job.contains("websocket")||job.contains("消息")||job.contains("redis")||job.contains("rabbitmq"))&&(lower.contains("websocket")||lower.contains("rabbitmq")))score+=12;if((job.contains("react")||job.contains("前端")||job.contains("全栈")||job.contains("electron"))&&(lower.contains("react")||lower.contains("typescript")))score+=12;if((job.contains("ai")||job.contains("大模型")||job.contains("agent"))&&(lower.contains("qwen")||lower.contains("大模型")||lower.contains("ai")))score+=14;if((job.contains("事务")||job.contains("权限")||job.contains("业务系统"))&&(lower.contains("事务")||lower.contains("权限")))score+=10;if(job.contains("测试")&&lower.contains("jmeter"))score+=10;return score;}
    private String projectLabel(String title,String text){String lower=(title+" "+text).toLowerCase(Locale.ROOT);if(lower.contains("即时通信"))return"即时通信系统";if(lower.contains("医院管理"))return"Spring Boot业务系统";if(lower.contains("播放器"))return"React桌面端项目";return title.replaceAll("(?i)基于|开发的|项目$","").replaceAll("\\s+"," ").trim();}
    private String projectFact(String text,String job){String lower=text.toLowerCase(Locale.ROOT);if((job.contains("ai")||job.contains("大模型")||job.contains("agent"))&&(lower.contains("qwen")||lower.contains("大模型")))return"大模型接入与超时降级";if((job.contains("websocket")||job.contains("消息")||job.contains("即时通信"))&&lower.contains("websocket"))return"单群聊、未读统计与断线重连";if((job.contains("mq")||job.contains("消息队列")||job.contains("rabbitmq"))&&lower.contains("rabbitmq"))return"消息分发、幂等与失败重试";if((job.contains("前端")||job.contains("全栈")||job.contains("electron"))&&(lower.contains("electron")||lower.contains("多数据源")))return"前后端接口与多数据源联调";if(job.contains("测试")&&lower.contains("jmeter"))return"接口与性能测试";if(lower.contains("事务")||lower.contains("权限"))return"接口、事务和角色权限控制";if(lower.contains("websocket"))return"接口、数据模型和消息处理";return"接口、数据模型和异常处理";}
    private String question(String job){if(job.contains("前端")||job.contains("全栈"))return"请问岗位目前更偏前端还是后端？";if(job.contains("ai")||job.contains("大模型")||job.contains("agent"))return"方便了解下岗位主要应用场景吗？";if(job.contains("测试"))return"请问岗位更偏自动化测试还是平台开发？";if(job.contains("websocket")||job.contains("实时通信"))return"请问团队主要是哪类实时业务场景？";return"请问岗位目前还在招聘吗？";}
    private String roleFamily(String job){if(job.contains("全栈"))return"FULLSTACK";if(job.contains("前端"))return"FRONTEND";if(job.contains("ai")||job.contains("大模型"))return"AI_APPLICATION";if(job.contains("java")||job.contains("后端"))return"JAVA_BACKEND";return"GENERAL_DEVELOPMENT";}
    private String normalizeRole(String raw,String job){String value=Objects.toString(raw,"").replaceAll("[|｜].*$","").replaceAll("(?i)\\(J?\\d{4,}\\)","").replaceAll("[（(][^）)]*(双休|社保|高薪|急招|考研|地点|福利)[^）)]*[）)]","").trim();if(job.contains("ai")||job.contains("大模型")||job.contains("agent"))return"AI应用开发岗位";if(job.contains("测试"))return"测试开发岗位";if(job.contains("全栈"))return"全栈开发岗位";if(job.contains("前端"))return"前端开发岗位";if(job.contains("java"))return"Java开发岗位";if(job.contains("后端"))return"后端开发岗位";value=value.replaceAll("(?i)JAVA","Java");return value.isBlank()?"软件开发岗位":value.endsWith("岗位")?value:value+"岗位";}
    private String opening(String key,String role){return switch(Math.floorMod(Objects.toString(key,"").hashCode(),3)){case 0->"您好，看到您发布的"+role+"。";case 1->"您好，看到您在招"+role+"。";default->"您好，我对这个"+role+"很感兴趣。";};}
    private boolean isCampus(String job){return job.contains("实习")||job.contains("校招")||job.contains("应届");}
    private String factPhrase(String fact){return switch(fact){case"接口、事务和角色权限控制"->"负责接口开发、事务处理和权限控制";case"单群聊、未读统计与断线重连"->"实现单群聊、未读统计和断线重连";case"消息分发、幂等与失败重试"->"处理消息分发、幂等和失败重试";case"前后端接口与多数据源联调"->"完成前后端接口和多数据源联调";case"大模型接入与超时降级"->"完成大模型接入和超时降级";case"接口与性能测试"->"完成接口和性能测试";default->"完成"+fact;};}
    private String chooseLength(String primary,String fallback,String role,int max){if(primary.length()<=max)return primary;if(fallback.length()<=max)return fallback;String safe=normalizeGreeting("您好，我是软件工程应届生，有Java项目实践，想应聘贵司"+role+"。请问岗位目前还在招聘吗？");return safe.length()<=max?safe:"您好，我是软件工程应届生，有Java项目实践，请问岗位目前还在招聘吗？";}
    private String normalizeGreeting(String value){return value.replaceAll("\\s+"," ").replaceAll("\\s*([，。；？！])\\s*","$1").replaceAll("([。！？，；])\\1+","$1").replace("实现基于","基于").replaceAll("。([？?])","$1").trim();}
    private int qualityScore(String text,Map<String,Object> evidence,List<String> recent,String role){
        int score=0;int length=text.length();if(length>=50&&length<=85)score+=15;else if(length>=35&&length<=95)score+=8;if(text.contains(role.replace("岗位","")))score+=10;
        String label=Objects.toString(evidence.get("projectLabel"),"");if(!label.isBlank()&&text.contains(label))score+=20;String fact=Objects.toString(evidence.get("factPhrase"),Objects.toString(evidence.get("fact"),""));if(!fact.isBlank()&&text.contains(fact))score+=20;
        if(evidence.get("skills") instanceof List<?> skills&&skills.stream().map(String::valueOf).anyMatch(text::contains))score+=25;if(text.contains("？")||text.contains("?"))score+=10;if(text.contains("关注贵司")||text.contains("期待进一步沟通"))score-=10;
        double similarity=recent.stream().filter(Objects::nonNull).mapToDouble(item->dice(text,item)).max().orElse(0);if(similarity>.88)score-=15;else if(similarity>.78)score-=8;return Math.max(0,Math.min(100,score));
    }
    private double dice(String left,String right){Set<String>a=bigrams(left),b=bigrams(right);if(a.isEmpty()||b.isEmpty())return 0;long common=a.stream().filter(b::contains).count();return 2d*common/(a.size()+b.size());}
    private Set<String> bigrams(String value){String text=Objects.toString(value,"").replaceAll("\\s+","");Set<String> result=new HashSet<>();for(int i=0;i+1<text.length();i++)result.add(text.substring(i,i+2));return result;}
    private boolean balanced(String value,char left,char right){int balance=0;for(char ch:value.toCharArray()){if(ch==left)balance++;if(ch==right&&--balance<0)return false;}return balance==0;}
    private String nonBlank(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private boolean containsIgnoreCase(String text,String value){return Objects.toString(text,"").toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));}
    private String style(String value){return Set.of("PROJECT_EVIDENCE","SKILL_MATCH","SHORT_QUESTION").contains(value)?value:"SKILL_MATCH";}
    private List<String> strings(String json){return jsons.list(json).stream().map(String::valueOf).toList();}
    private List<String> clean(List<String> values,int max){return values==null?List.of():values.stream().map(String::trim).filter(v->!v.isBlank()).distinct().limit(max).toList();}

    public record PolicyUpdate(UUID activeResumeVersionId,String defaultStyle,boolean includeQuestion,int maxLength,List<String>bannedWords,List<String>preferredWords,boolean aiEnabled){}
    public record PolicyView(UUID activeResumeVersionId,String defaultStyle,boolean includeQuestion,int maxLength,List<String>bannedWords,List<String>preferredWords,boolean aiEnabled,List<ResumeOption> resumes){}
    public record ResumeOption(UUID versionId,String title,int version,boolean usable){}
    public record Candidate(String style,String text,Map<String,Object> evidence,List<String>warnings,boolean valid,int qualityScore){}
    public record Generated(String text,String style,Map<String,Object> evidence,List<Candidate> candidates){}
    private record ProjectFact(String title,String label,String fact,List<String> skills,int score){}
}
