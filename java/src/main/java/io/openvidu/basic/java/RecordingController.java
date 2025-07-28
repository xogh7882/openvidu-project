package io.openvidu.basic.java;

import io.openvidu.basic.java.service.RecordingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/recordings")
public class RecordingController {

    @Autowired
    private RecordingService recordingService;

    @PostMapping("/start")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> startRecording(@RequestBody Map<String, String> params) {
        String roomName = params.get("roomName");
        
        if (roomName == null || roomName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(Map.of("errorMessage", "roomName is required"))
            );
        }

        return recordingService.startRecording(roomName)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> {
                    String message = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                    if (message.contains("already started")) {
                        return ResponseEntity.status(409).body(Map.of("errorMessage", message));
                    }
                    return ResponseEntity.status(500).body(Map.of("errorMessage", "Error starting recording"));
                });
    }

    @PostMapping("/stop")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> stopRecording(@RequestBody Map<String, String> params) {
        String roomName = params.get("roomName");
        
        if (roomName == null || roomName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(Map.of("errorMessage", "roomName is required"))
            );
        }

        return recordingService.stopRecording(roomName)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> {
                    String message = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                    if (message.contains("not started")) {
                        return ResponseEntity.status(409).body(Map.of("errorMessage", message));
                    }
                    return ResponseEntity.status(500).body(Map.of("errorMessage", "Error stopping recording"));
                });
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRecordings(@RequestParam(required = false) String roomId) {
        try {
            List<Map<String, String>> recordings = recordingService.listRecordings(roomId);
            return ResponseEntity.ok(Map.of("recordings", recordings));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("errorMessage", "Error listing recordings"));
        }
    }

    @GetMapping("/{recordingName}")
    public ResponseEntity<Resource> getRecording(@PathVariable String recordingName, 
                                               @RequestHeader(value = "Range", required = false) String rangeHeader) {
        try {
            return recordingService.getRecordingStream(recordingName, rangeHeader);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    @DeleteMapping("/{recordingName}")
    public ResponseEntity<Map<String, String>> deleteRecording(@PathVariable String recordingName) {
        try {
            boolean deleted = recordingService.deleteRecording(recordingName);
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "Recording deleted"));
            } else {
                return ResponseEntity.status(404).body(Map.of("errorMessage", "Recording not found"));
            }
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("errorMessage", "Error deleting recording"));
        }
    }
}