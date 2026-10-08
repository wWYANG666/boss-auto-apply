package com.careerlens.core.resume;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.regex.Pattern;

@Component @RequiredArgsConstructor
public class ImportMapper {
    private final ObjectMapper mapper;
    public ObjectNode candidate(Map<String,Object> parsed) {
        JsonNode source=mapper.valueToTree(parsed);
        if(source.path("structuredContent").isObject()
                && source.path("structuredContent").path("profile").isObject()
                && source.path("structuredContent").path("sections").isArray()){
            ObjectNode candidate=source.path("structuredContent").deepCopy();
            ObjectNode original=source.deepCopy();
            original.remove("structuredContent");
            original.set("importAnalysis",candidate.path("importAnalysis"));
            candidate.put("importReviewPending",true);
            candidate.set("sourceDocument",original);
            return candidate;
        }
        var content=mapper.createObjectNode();content.put("schemaVersion","2.0");content.put("importReviewPending",true);
        var profile=content.putObject("profile");
        String raw=source.path("rawText").asText();
        var email=Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(raw);
        if(email.find())profile.put("email",email.group());
        var phone=Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])").matcher(raw);
        if(phone.find())profile.put("phone",phone.group());
        ArrayNode sections=content.putArray("sections");
        for(JsonNode section:source.path("sections")){
            String type=section.path("type").asText("OTHER");
            if("PROFILE".equals(type))type="OTHER";
            ObjectNode s=sections.addObject();s.put("elementId",section.path("elementId").asText(UUID.randomUUID().toString()));
            s.put("type",type);s.put("heading",section.path("heading").asText("待确认内容"));s.put("hidden",false);
            s.put("confidence",section.path("confidence").asDouble(0));s.set("evidenceIds",section.path("evidenceIds"));
            ArrayNode items=s.putArray("items");
            if("SKILLS".equals(type)){
                for(String line:section.path("text").asText().split("[,，、\\n]"))if(!line.isBlank())items.add(line.trim());
            }else{
                ObjectNode item=items.addObject();item.put("elementId",UUID.randomUUID().toString());
                item.put("description",section.path("text").asText());item.put("hidden",false);
            }
        }
        content.set("sourceDocument",source);
        return content;
    }
}
