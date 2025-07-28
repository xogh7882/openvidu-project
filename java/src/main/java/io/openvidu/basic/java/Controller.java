package io.openvidu.basic.java;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.WebhookReceiver;
import io.livekit.server.EgressServiceClient;
import livekit.LivekitWebhook.WebhookEvent;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitEgress.EncodedFileOutput;
import livekit.LivekitEgress.EncodedFileType;

@CrossOrigin(origins = "*")
@RestController
public class Controller {

	@Value("${livekit.api.key}")
	private String LIVEKIT_API_KEY;

	@Value("${livekit.api.secret}")
	private String LIVEKIT_API_SECRET;

	@Value("${livekit.server.url:ws://localhost:7880}")
	private String LIVEKIT_SERVER_URL;

	@Value("${recordings.path:/recordings}")
	private String RECORDINGS_PATH;

	// In-memory storage for active recordings (room name -> recording info)
	private final Map<String, Map<String, Object>> activeRecordings = new ConcurrentHashMap<>();

	/**
	 * @param params JSON object with roomName and participantName
	 * @return JSON object with the JWT token
	 */
	@PostMapping(value = "/token")
	public ResponseEntity<Map<String, String>> createToken(@RequestBody Map<String, String> params) {
		String roomName = params.get("roomName");
		String participantName = params.get("participantName");

		if (roomName == null || participantName == null) {
			return ResponseEntity.badRequest().body(Map.of("errorMessage", "roomName and participantName are required"));
		}

		AccessToken token = new AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET);
		token.setName(participantName);
		token.setIdentity(participantName);
		token.addGrants(new RoomJoin(true), new RoomName(roomName));

		return ResponseEntity.ok(Map.of("token", token.toJwt()));
	}

	@PostMapping(value = "/livekit/webhook", consumes = "application/webhook+json")
	public ResponseEntity<String> receiveWebhook(@RequestHeader("Authorization") String authHeader, @RequestBody String body) {
		WebhookReceiver webhookReceiver = new WebhookReceiver(LIVEKIT_API_KEY, LIVEKIT_API_SECRET);
		try {
			WebhookEvent event = webhookReceiver.receive(body, authHeader);
			System.out.println("LiveKit Webhook: " + event.toString());
		} catch (Exception e) {
			System.err.println("Error validating webhook event: " + e.getMessage());
		}
		return ResponseEntity.ok("ok");
	}

	/**
	 * Start a recording for a room
	 * @param params JSON object with roomName
	 * @return JSON object with recording info
	 */
	@PostMapping(value = "/recordings/start")
	public ResponseEntity<Map<String, Object>> startRecording(@RequestBody Map<String, String> params) {
		String roomName = params.get("roomName");

		if (roomName == null) {
			return ResponseEntity.badRequest().body(Map.of("errorMessage", "roomName is required"));
		}

		// Check if there is already an active recording for this room
		Map<String, Object> activeRecording = getActiveRecordingByRoom(roomName);
		if (activeRecording != null) {
			return ResponseEntity.status(409).body(Map.of("errorMessage", "Recording already started for this room"));
		}

		// Use the EncodedFileOutput to save the recording to an MP4 file
		EncodedFileOutput fileOutput = EncodedFileOutput.newBuilder()
				.setFileType(EncodedFileType.MP4)
				.setFilepath(RECORDINGS_PATH + "/{room_name}-{time}-{room_id}")
				.setDisableManifest(true)
				.build();

		try {
			// EgressServiceClient requires 3 parameters: apiKey, apiSecret, host
			EgressServiceClient egressClient = EgressServiceClient.createClient(LIVEKIT_API_KEY, LIVEKIT_API_SECRET, LIVEKIT_SERVER_URL);

			// Start a RoomCompositeEgress using the correct method signature
			retrofit2.Call<EgressInfo> egressCall = egressClient.startRoomCompositeEgress(roomName, fileOutput);
			retrofit2.Response<EgressInfo> response = egressCall.execute();

			if (!response.isSuccessful()) {
				throw new Exception("Failed to start recording: " + response.message());
			}

			EgressInfo egressInfo = response.body();

			String filename = egressInfo.getFileResultsList().get(0).getFilename();
			String recordingName = filename.substring(filename.lastIndexOf("/") + 1);
			long startedAt = egressInfo.getStartedAt() / 1_000_000L;

			Map<String, Object> recording = Map.of(
					"name", recordingName,
					"startedAt", startedAt
			);

			// Store active recording info
			activeRecordings.put(roomName, recording);

			return ResponseEntity.ok(Map.of(
					"message", "Recording started",
					"recording", recording
			));

		} catch (Exception error) {
			System.err.println("Error starting recording: " + error.getMessage());
			return ResponseEntity.status(500).body(Map.of("errorMessage", "Error starting recording"));
		}
	}

	/**
	 * Get active recording by room name
	 */
	private Map<String, Object> getActiveRecordingByRoom(String roomName) {
		return activeRecordings.get(roomName);
	}

	/**
	 * Stop a recording for a room
	 * @param params JSON object with roomName
	 * @return JSON object with recording info
	 */
	@PostMapping(value = "/recordings/stop")
	public ResponseEntity<Map<String, Object>> stopRecording(@RequestBody Map<String, String> params) {
		String roomName = params.get("roomName");

		if (roomName == null) {
			return ResponseEntity.badRequest().body(Map.of("errorMessage", "roomName is required"));
		}

		// Check if there is an active recording for this room
		Map<String, Object> activeRecording = getActiveRecordingByRoom(roomName);
		if (activeRecording == null) {
			return ResponseEntity.status(409).body(Map.of("errorMessage", "Recording not started for this room"));
		}

		try {
			// EgressServiceClient requires 3 parameters: apiKey, apiSecret, host
			EgressServiceClient egressClient = EgressServiceClient.createClient(LIVEKIT_API_KEY, LIVEKIT_API_SECRET, LIVEKIT_SERVER_URL);

			// Stop the egress to finish the recording
			String egressId = (String) activeRecording.get("egressId");
			if (egressId == null) {
				throw new Exception("No egress ID found for active recording");
			}

			retrofit2.Call<EgressInfo> egressCall = egressClient.stopEgress(egressId);
			retrofit2.Response<EgressInfo> response = egressCall.execute();

			if (!response.isSuccessful()) {
				throw new Exception("Failed to stop recording: " + response.message());
			}

			EgressInfo egressInfo = response.body();
			String filename = egressInfo.getFileResultsList().get(0).getFilename();
			String recordingName = filename.substring(filename.lastIndexOf("/") + 1);

			Map<String, Object> recording = Map.of("name", recordingName);

			// Remove active recording info
			activeRecordings.remove(roomName);

			return ResponseEntity.ok(Map.of(
					"message", "Recording stopped",
					"recording", recording
			));

		} catch (Exception error) {
			System.err.println("Error stopping recording: " + error.getMessage());
			return ResponseEntity.status(500).body(Map.of("errorMessage", "Error stopping recording"));
		}
	}
}