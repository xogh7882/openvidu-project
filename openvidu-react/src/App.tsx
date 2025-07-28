import {
    LocalVideoTrack,
    RemoteParticipant,
    RemoteTrack,
    RemoteTrackPublication,
    Room,
    RoomEvent
} from "livekit-client";
import "./App.css";
import { useState } from "react";
import VideoComponent from "./components/VideoComponent";
import AudioComponent from "./components/AudioComponent";

type TrackInfo = {
    trackPublication: RemoteTrackPublication;
    participantIdentity: string;
};

type Recording = {
    name: string;
    startedAt?: number;
};

// When running OpenVidu locally, leave these variables empty
// For other deployment type, configure them with correct URLs depending on your deployment
let APPLICATION_SERVER_URL = "";
let LIVEKIT_URL = "";
configureUrls();

function configureUrls() {
    // If APPLICATION_SERVER_URL is not configured, use default value from OpenVidu Local deployment
    if (!APPLICATION_SERVER_URL) {
        if (window.location.hostname === "localhost") {
            APPLICATION_SERVER_URL = "http://localhost:6080/";
        } else {
            APPLICATION_SERVER_URL = "https://" + window.location.hostname + ":6443/";
        }
    }

    // If LIVEKIT_URL is not configured, use default value from OpenVidu Local deployment
    if (!LIVEKIT_URL) {
        if (window.location.hostname === "localhost") {
            LIVEKIT_URL = "ws://localhost:7880/";
        } else {
            LIVEKIT_URL = "wss://" + window.location.hostname + ":7443/";
        }
    }
}

function App() {
    const [room, setRoom] = useState<Room | undefined>(undefined);
    const [localTrack, setLocalTrack] = useState<LocalVideoTrack | undefined>(undefined);
    const [remoteTracks, setRemoteTracks] = useState<TrackInfo[]>([]);

    const [participantName, setParticipantName] = useState("Participant" + Math.floor(Math.random() * 100));
    const [roomName, setRoomName] = useState("Test Room");

    // Recording states
    const [isRecording, setIsRecording] = useState(false);
    const [recordings, setRecordings] = useState<Recording[]>([]);
    const [selectedRecording, setSelectedRecording] = useState<string | null>(null);
    const [recordingButtonDisabled, setRecordingButtonDisabled] = useState(false);

    async function joinRoom() {
        // Initialize a new Room object
        const room = new Room();
        setRoom(room);

        // Specify the actions when events take place in the room
        // On every new Track received...
        room.on(
            RoomEvent.TrackSubscribed,
            (_track: RemoteTrack, publication: RemoteTrackPublication, participant: RemoteParticipant) => {
                setRemoteTracks((prev) => [
                    ...prev,
                    { trackPublication: publication, participantIdentity: participant.identity }
                ]);
            }
        );

        // On every Track destroyed...
        room.on(RoomEvent.TrackUnsubscribed, (_track: RemoteTrack, publication: RemoteTrackPublication) => {
            setRemoteTracks((prev) => prev.filter((track) => track.trackPublication.trackSid !== publication.trackSid));
        });

        // On recording status changed...
        room.on(RoomEvent.RecordingStatusChanged, (recording: boolean) => {
            setIsRecording(recording);
            setRecordingButtonDisabled(false);
            updateRecordingList();
        });

        try {
            // Get a token from your application server with the room name and participant name
            const token = await getToken(roomName, participantName);

            // Connect to the room with the LiveKit URL and the token
            await room.connect(LIVEKIT_URL, token);

            // Publish your camera and microphone
            await room.localParticipant.enableCameraAndMicrophone();
            setLocalTrack(room.localParticipant.videoTrackPublications.values().next().value.videoTrack);
        } catch (error) {
            console.log("There was an error connecting to the room:", (error as Error).message);
            await leaveRoom();
        }
    }

    async function leaveRoom() {
        // Leave the room by calling 'disconnect' method over the Room object
        await room?.disconnect();

        // Reset the state
        setRoom(undefined);
        setLocalTrack(undefined);
        setRemoteTracks([]);
        setIsRecording(false);
        setRecordings([]);
        setSelectedRecording(null);
        setRecordingButtonDisabled(false);
    }

    /**
     * --------------------------------------------
     * GETTING A TOKEN FROM YOUR APPLICATION SERVER
     * --------------------------------------------
     * The method below request the creation of a token to
     * your application server. This prevents the need to expose
     * your LiveKit API key and secret to the client side.
     *
     * In this sample code, there is no user control at all. Anybody could
     * access your application server endpoints. In a real production
     * environment, your application server must identify the user to allow
     * access to the endpoints.
     */
    async function getToken(roomName: string, participantName: string) {
        const response = await fetch(APPLICATION_SERVER_URL + "token", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                roomName: roomName,
                participantName: participantName
            })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(`Failed to get token: ${error.errorMessage}`);
        }

        const data = await response.json();
        return data.token;
    }

    // Recording functions
    async function manageRecording() {
        if (!room) return;

        setRecordingButtonDisabled(true);

        try {
            if (isRecording) {
                await stopRecording();
            } else {
                await startRecording();
            }
        } catch (error) {
            console.error("Error managing recording:", error);
            setRecordingButtonDisabled(false);
        }
    }

    async function startRecording() {
        const response = await fetch(APPLICATION_SERVER_URL + "recordings/start", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                roomName: roomName
            })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(`Failed to start recording: ${error.errorMessage}`);
        }

        const data = await response.json();
        console.log("Recording started:", data);
    }

    async function stopRecording() {
        const response = await fetch(APPLICATION_SERVER_URL + "recordings/stop", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                roomName: roomName
            })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(`Failed to stop recording: ${error.errorMessage}`);
        }

        const data = await response.json();
        console.log("Recording stopped:", data);
    }

    async function updateRecordingList() {
        if (!room) return;

        try {
            const roomSid = room.sid;
            const url = `${APPLICATION_SERVER_URL}recordings${roomSid ? `?roomId=${roomSid}` : ''}`;
            
            const response = await fetch(url);
            if (response.ok) {
                const data = await response.json();
                setRecordings(data.recordings || []);
            }
        } catch (error) {
            console.error("Error fetching recordings:", error);
        }
    }

    async function deleteRecording(recordingName: string) {
        try {
            const response = await fetch(`${APPLICATION_SERVER_URL}recordings/${recordingName}`, {
                method: "DELETE"
            });

            if (response.ok || response.status === 404) {
                await updateRecordingList();
            }
        } catch (error) {
            console.error("Error deleting recording:", error);
        }
    }

    function playRecording(recordingName: string) {
        setSelectedRecording(recordingName);
    }

    function closeRecordingModal() {
        setSelectedRecording(null);
    }

    return (
        <>
            {!room ? (
                <div id="join">
                    <div id="join-dialog">
                        <h2>Join a Video Room</h2>
                        <form
                            onSubmit={(e) => {
                                joinRoom();
                                e.preventDefault();
                            }}
                        >
                            <div>
                                <label htmlFor="participant-name">Participant</label>
                                <input
                                    id="participant-name"
                                    className="form-control"
                                    type="text"
                                    value={participantName}
                                    onChange={(e) => setParticipantName(e.target.value)}
                                    required
                                />
                            </div>
                            <div>
                                <label htmlFor="room-name">Room</label>
                                <input
                                    id="room-name"
                                    className="form-control"
                                    type="text"
                                    value={roomName}
                                    onChange={(e) => setRoomName(e.target.value)}
                                    required
                                />
                            </div>
                            <button
                                className="btn btn-lg btn-success"
                                type="submit"
                                disabled={!roomName || !participantName}
                            >
                                Join!
                            </button>
                        </form>
                    </div>
                </div>
            ) : (
                <div id="room">
                    <div id="room-header">
                        <h2 id="room-title">{roomName}</h2>
                        <button className="btn btn-danger" id="leave-room-button" onClick={leaveRoom}>
                            Leave Room
                        </button>
                    </div>
                    
                    {/* Recording Controls */}
                    <div id="recording-controls" style={{ margin: "20px 0", textAlign: "center" }}>
                        <button 
                            className={`btn ${isRecording ? 'btn-danger' : 'btn-primary'}`}
                            onClick={manageRecording}
                            disabled={recordingButtonDisabled}
                            style={{ marginRight: "10px" }}
                        >
                            {recordingButtonDisabled 
                                ? (isRecording ? "Stopping..." : "Starting...") 
                                : (isRecording ? "Stop Recording" : "Start Recording")
                            }
                        </button>
                        {isRecording && (
                            <span id="recording-text" style={{ color: "red", fontWeight: "bold" }}>
                                Room is being recorded
                            </span>
                        )}
                    </div>

                    <div id="layout-container">
                        {localTrack && (
                            <VideoComponent track={localTrack} participantIdentity={participantName} local={true} />
                        )}
                        {remoteTracks.map((remoteTrack) =>
                            remoteTrack.trackPublication.kind === "video" ? (
                                <VideoComponent
                                    key={remoteTrack.trackPublication.trackSid}
                                    track={remoteTrack.trackPublication.videoTrack!}
                                    participantIdentity={remoteTrack.participantIdentity}
                                />
                            ) : (
                                <AudioComponent
                                    key={remoteTrack.trackPublication.trackSid}
                                    track={remoteTrack.trackPublication.audioTrack!}
                                />
                            )
                        )}
                    </div>

                    {/* Recordings List */}
                    {recordings.length > 0 && (
                        <div id="recordings" style={{ margin: "20px", padding: "20px", border: "1px solid #ccc", borderRadius: "5px" }}>
                            <h3>Recordings</h3>
                            <div id="recording-list">
                                {recordings.map((recording) => (
                                    <div key={recording.name} className="recording-item" style={{ 
                                        display: "flex", 
                                        justifyContent: "space-between", 
                                        alignItems: "center", 
                                        padding: "10px", 
                                        margin: "5px 0", 
                                        border: "1px solid #ddd", 
                                        borderRadius: "3px" 
                                    }}>
                                        <span>{recording.name}</span>
                                        <div>
                                            <button 
                                                className="btn btn-sm btn-primary" 
                                                onClick={() => playRecording(recording.name)}
                                                style={{ marginRight: "5px" }}
                                            >
                                                Play
                                            </button>
                                            <button 
                                                className="btn btn-sm btn-danger" 
                                                onClick={() => deleteRecording(recording.name)}
                                            >
                                                Delete
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Recording Video Modal */}
                    {selectedRecording && (
                        <div id="recording-modal" style={{
                            position: "fixed",
                            top: 0,
                            left: 0,
                            width: "100%",
                            height: "100%",
                            backgroundColor: "rgba(0,0,0,0.8)",
                            display: "flex",
                            justifyContent: "center",
                            alignItems: "center",
                            zIndex: 1000
                        }}>
                            <div style={{
                                backgroundColor: "white",
                                padding: "20px",
                                borderRadius: "10px",
                                maxWidth: "90%",
                                maxHeight: "90%"
                            }}>
                                <video
                                    controls
                                    autoPlay
                                    style={{ width: "100%", maxWidth: "800px" }}
                                    src={`${APPLICATION_SERVER_URL}recordings/${selectedRecording}`}
                                />
                                <div style={{ textAlign: "center", marginTop: "10px" }}>
                                    <button className="btn btn-secondary" onClick={closeRecordingModal}>
                                        Close
                                    </button>
                                </div>
                            </div>
                        </div>
                    )}
                </div>
            )}
        </>
    );
}

export default App;
