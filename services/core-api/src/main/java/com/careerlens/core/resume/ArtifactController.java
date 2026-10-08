package com.careerlens.core.resume;
import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.ApiException;
import com.careerlens.core.integration.AiWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController @RequiredArgsConstructor
public class ArtifactController {
 private final ResumeService resumes;
 private final AiWorkerClient ai;
 private final ObjectMapper mapper;
 private final JdbcTemplate jdbc;
 @Value("${careerlens.artifacts.directory:./data/artifacts}") private String directory;
 @PostMapping("/api/v1/resume-versions/{versionId}/artifacts")
 synchronized Map<String,Object> create(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID versionId) throws Exception{
   var version=resumes.version(user.id(),versionId);
   var response=ai.renderResume(mapper.readTree(version.getContentText()));
   if(!(response.get("contentBase64") instanceof String encoded))throw ApiException.conflict("RENDER_FAILED","PDF生成服务未就绪或字体缺失");
   byte[] bytes=Base64.getDecoder().decode(encoded);
   String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
   if(!hash.equals(response.get("sha256")))throw ApiException.conflict("ARTIFACT_HASH_MISMATCH","文件校验失败");
   String font=Objects.toString(response.get("fontSha256")),template=Objects.toString(response.get("templateVersion"));
   List<UUID> existing=jdbc.query("SELECT id FROM resume_artifact WHERE user_id=? AND resume_version_id=? AND template_version=? AND font_sha256=?",
     (rs,n)->rs.getObject(1,UUID.class),user.id(),versionId,template,font);
   if(!existing.isEmpty())return get(user,existing.get(0));
   UUID id=UUID.randomUUID();Path folder=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(folder);
   try {Files.write(folder.resolve(hash+".pdf"),bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
   jdbc.update("INSERT INTO resume_artifact(id,user_id,resume_version_id,sha256,font_sha256,template_version,byte_size,page_count,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
      id,user.id(),versionId,hash,font,template,bytes.length,((Number)response.get("pageCount")).intValue(),java.sql.Timestamp.from(Instant.now()));
   return get(user,id);
 }
 @GetMapping("/api/v1/artifacts/{id}")
 Map<String,Object> get(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){
   var rows=jdbc.query("SELECT * FROM resume_artifact WHERE id=? AND user_id=?",(rs,n)->Map.<String,Object>of(
    "id",rs.getString("id"),"resumeVersionId",rs.getString("resume_version_id"),"sha256",rs.getString("sha256"),
    "pageCount",rs.getInt("page_count"),"size",rs.getLong("byte_size"),"templateVersion",rs.getString("template_version")),
    id,user.id());
   if(rows.isEmpty())throw ApiException.notFound("导出文件");return rows.get(0);
 }
 @GetMapping("/api/v1/artifacts/{id}/file")
 ResponseEntity<byte[]> download(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id) throws Exception{
   var metadata=get(user,id);String hash=metadata.get("sha256").toString();
   byte[] bytes=Files.readAllBytes(Path.of(directory).toAbsolutePath().normalize().resolve(hash+".pdf"));
   if(!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash))
     throw ApiException.conflict("ARTIFACT_CORRUPTED","导出文件校验失败");
   return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
     .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=resume-"+id+".pdf").body(bytes);
 }
}
