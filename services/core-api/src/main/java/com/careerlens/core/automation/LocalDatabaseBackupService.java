package com.careerlens.core.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;

@Service
@Profile("local")
@RequiredArgsConstructor
public class LocalDatabaseBackupService {
    private final JdbcTemplate jdbc;
    @Value("${careerlens.local-backup.directory:./data/backups}") private String directory;
    @Value("${careerlens.local-backup.keep:7}") private int keep;

    @Scheduled(cron="${careerlens.local-backup.cron:0 10 4 * * *}")
    public void scheduled(){try{backup();}catch(Exception ignored){}}

    public synchronized Map<String,Object> backup() throws java.io.IOException {
        Path folder=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(folder);
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path target=folder.resolve("careerlens-"+timestamp+".zip");
        jdbc.execute("BACKUP TO '"+target.toString().replace("'","''")+"'");
        try(var files=Files.list(folder)){
            var old=files.filter(path->path.getFileName().toString().matches("careerlens-\\d{8}-\\d{6}\\.zip"))
                    .sorted(Comparator.comparingLong(this::modified).reversed()).skip(Math.max(1,keep)).toList();
            for(Path path:old)Files.deleteIfExists(path);
        }
        return Map.of("path",target.toString(),"bytes",Files.size(target),"kept",Math.max(1,keep));
    }
    public Map<String,Object> status() throws java.io.IOException {
        Path folder=Path.of(directory).toAbsolutePath().normalize();if(!Files.isDirectory(folder))return Map.of("count",0,"bytes",0,"directory",folder.toString());
        try(var files=Files.list(folder)){var list=files.filter(Files::isRegularFile).toList();long bytes=0;for(Path path:list)bytes+=Files.size(path);return Map.of("count",list.size(),"bytes",bytes,"directory",folder.toString());}
    }
    private long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(Exception ignored){return 0;}}
}
