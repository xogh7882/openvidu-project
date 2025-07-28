package io.openvidu.basic.java.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class RecordingService {

    @Value("${livekit.url}")
    private String livekitUrl;

    @Value("${livekit.api.key}")
    private String livekitApiKey;

    @Value("${livekit.api.secret}")
    private String livekitApiSecret;

    @Value("${recording.path}")
    private String recordingPath;

    @Value("${recording.file.portion.size}")
    private long recordingFilePortionSize;

    // 활성 녹화 추적을 위한 간단한 메모리 저장소
    private final Map<String, String> activeRecordings = new java.util.concurrent.ConcurrentHashMap<>();

    public CompletableFuture<Map<String, Object>> startRecording(String roomName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check if there's already an active recording
                if (activeRecordings.containsKey(roomName)) {
                    throw new RuntimeException("Recording already started for this room");
                }

                // Create recordings directory if it doesn't exist
                Path recordingsDir = Paths.get(recordingPath);
                if (!Files.exists(recordingsDir)) {
                    Files.createDirectories(recordingsDir);
                }

                // 간단한 시뮬레이션: 실제 녹화 ID 생성
                String recordingId = "recording_" + roomName + "_" + System.currentTimeMillis();
                String recordingName = roomName + "-" + System.currentTimeMillis() + ".mp4";
                
                // 활성 녹화 목록에 추가
                activeRecordings.put(roomName, recordingId);
                
                long startedAt = System.currentTimeMillis();

                return Map.of(
                    "message", "Recording started",
                    "recording", Map.of(
                        "name", recordingName,
                        "startedAt", startedAt
                    )
                );
            } catch (Exception e) {
                throw new RuntimeException("Error starting recording: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Map<String, Object>> stopRecording(String roomName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String recordingId = activeRecordings.get(roomName);
                if (recordingId == null) {
                    throw new RuntimeException("Recording not started for this room");
                }

                // 활성 녹화 목록에서 제거
                activeRecordings.remove(roomName);
                
                // 더미 녹화 파일 생성 (테스트용)
                String recordingName = roomName + "-" + System.currentTimeMillis() + ".mp4";
                Path recordingFile = Paths.get(recordingPath, recordingName);
                
                // 빈 MP4 파일 생성 (실제로는 LiveKit에서 생성됨)
                try {
                    Files.createFile(recordingFile);
                } catch (Exception e) {
                    // 파일이 이미 존재하거나 생성할 수 없는 경우 무시
                }

                return Map.of(
                    "message", "Recording stopped",
                    "recording", Map.of("name", recordingName)
                );
            } catch (Exception e) {
                throw new RuntimeException("Error stopping recording: " + e.getMessage(), e);
            }
        });
    }

    public List<Map<String, String>> listRecordings(String roomId) throws IOException {
        Path recordingsDir = Paths.get(recordingPath);
        if (!Files.exists(recordingsDir)) {
            return List.of();
        }

        return Files.list(recordingsDir)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".mp4"))
                .map(path -> path.getFileName().toString())
                .filter(name -> roomId == null || name.contains(roomId))
                .map(name -> Map.of("name", name))
                .collect(Collectors.toList());
    }

    public ResponseEntity<Resource> getRecordingStream(String recordingName, String rangeHeader) throws IOException {
        Path filePath = Paths.get(recordingPath, recordingName);
        
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        long fileSize = Files.size(filePath);
        long start = 0;
        long end = fileSize - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            try {
                start = Long.parseLong(ranges[0]);
                if (ranges.length > 1 && !ranges[1].isEmpty()) {
                    end = Long.parseLong(ranges[1]);
                } else {
                    end = Math.min(start + recordingFilePortionSize - 1, fileSize - 1);
                }
            } catch (NumberFormatException e) {
                // Invalid range, use default values
            }
        }

        if (start > end || start >= fileSize) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }

        end = Math.min(end, fileSize - 1);
        long contentLength = end - start + 1;

        InputStreamResource resource = new InputStreamResource(
            Files.newInputStream(filePath, StandardOpenOption.READ)
        );

        // Skip to start position
        resource.getInputStream().skip(start);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));
        headers.add("Accept-Ranges", "bytes");
        headers.setContentLength(contentLength);
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.setCacheControl("no-cache");

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .body(resource);
    }

    public boolean deleteRecording(String recordingName) throws IOException {
        Path filePath = Paths.get(recordingPath, recordingName);
        return Files.deleteIfExists(filePath);
    }

}