package com.genymobile.scrcpy;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

// ADDED FROM WS-SCRCPY : Extensiopn of the class, WebSocketServer
public class WSServer extends WebSocketServer {
    private static final String PID_FILE_PATH = "/data/local/tmp/ws_scrcpy.pid";
    public static final class SocketInfo {
        private static final HashSet<Short> INSTANCES_BY_ID = new HashSet<>();
        private final short id;
        private WebSocketConnection connection;

        SocketInfo(short id) {
            this.id = id;
            INSTANCES_BY_ID.add(id);
        }

        public static short getNextClientId() {
            short nextClientId = 0;
            while (INSTANCES_BY_ID.contains(++nextClientId)) {
                if (nextClientId == Short.MAX_VALUE) {
                    return -1;
                }
            }
            return nextClientId;
        }

        public short getId() {
            return id;
        }

        public WebSocketConnection getConnection() {
            return this.connection;
        }

        public void setConnection(WebSocketConnection connection) {
            this.connection = connection;
        }

        public void release() {
            INSTANCES_BY_ID.remove(id);
        }
    }

    protected final ControlMessageReader reader = new ControlMessageReader();
    private final Options options;
    private static final HashMap<Integer, WebSocketConnection> STREAM_BY_DISPLAY_ID = new HashMap<>();

    public WSServer(Options options) {
        super(new InetSocketAddress(options.getPortNumber()));
        this.options = options;
        unlinkPidFile();
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake handshake) {
        if (webSocket.isOpen()) {
            short clientId = SocketInfo.getNextClientId();
            if (clientId == -1) {
                webSocket.close(CloseFrame.TRY_AGAIN_LATER);
                return;
            }
            SocketInfo info = new SocketInfo(clientId);
            webSocket.setAttachment(info);
            WebSocketConnection.sendInitialInfo(WebSocketConnection.getInitialInfo(), webSocket, clientId);
            Ln.d("Client #" + clientId + " entered the room!");
        }
    }

    @Override
    public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
        Ln.d("Client has left the room!");
        FilePushHandler.cancelAllForConnection(webSocket);
        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo != null) {
            WebSocketConnection connection = socketInfo.getConnection();
            if (connection != null) {
                connection.leave(webSocket);
            }
            socketInfo.release();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String message) {
        String address = webSocket.getRemoteSocketAddress().getAddress().getHostAddress();
        ByteBuffer serializedMessage = toSerialize(message);
        Ln.d(serializedMessage.toString());
        Ln.d("BUFFER REMAINS : " + serializedMessage.remaining());
        this.onMessage(webSocket, serializedMessage);
        // try{
        //     ByteBuffer serializedMessage = toSerialize(message);
        //     Ln.d(serializedMessage.toString());
        //     this.onMessage(webSocket, serializedMessage);
        // }catch (Exception e){
        //     Ln.w("?  Client from " + address + " says: \"" + message + "\"");
        // }
        /**
         * TODO:
         *      1. ByteBuffer msg = toSerialize(message); //toSerialize는 json 형식의 message를 바이트형식으로 시리얼라이징을한다.
         *      2. this.onMessage(webSocket, msg);
         **/
    }

    private ByteBuffer toSerialize(String msg){
        try{
            ByteBuffer serializedMessage;
            JSONObject jsonObject = new JSONObject(msg);
            int type = jsonObject.getInt("type");
            Ln.d("PARSER ?? type = " + type);
            switch (type){
                case 0:
                    serializedMessage = ByteBuffer.allocate(14);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.put((byte)jsonObject.getInt("keyeventAction"));
                    Ln.d("keyeventAction written");
                    serializedMessage.putInt(jsonObject.getInt("keycode"));
                    Ln.d("keycode written");
                    serializedMessage.putInt(jsonObject.getInt("repeat"));
                    Ln.d("repeat written");
                    serializedMessage.putInt(jsonObject.getInt("metastate"));
                    Ln.d("metastate written");
                    serializedMessage.reset();
                    return serializedMessage;
                case 1:
                    byte[] text = jsonObject.getString("text").getBytes(StandardCharsets.UTF_8);
                    serializedMessage = ByteBuffer.allocate(text.length+5);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.putInt(text.length);
                    serializedMessage.put(text);
                    serializedMessage.reset();
                    return serializedMessage;
                case 2:
                    serializedMessage = ByteBuffer.allocate(28);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.put((byte)jsonObject.getInt("motioneventAction"));
                    serializedMessage.putLong(jsonObject.getInt("pointerId"));
                    serializedMessage.putInt(jsonObject.getInt("x"));
                    serializedMessage.putInt(jsonObject.getInt("y"));
                    serializedMessage.putShort((short)jsonObject.getInt("width"));
                    serializedMessage.putShort((short)jsonObject.getInt("height"));
                    double pressure = jsonObject.getDouble("pressure");
                    double adjusted;
                    if (pressure >= 0.0 && pressure <= 1.0){
                        adjusted = pressure*0x1p16f; //0x1p16f = 2^16
                    }else{
                        adjusted = 0;
                    }
                    serializedMessage.putShort((short)((int)adjusted & 0xffff)); //unsigned
                    serializedMessage.putInt(jsonObject.getInt("buttons"));
                    serializedMessage.reset();
                    return serializedMessage;
                case 3:
                    serializedMessage = ByteBuffer.allocate(21);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.putInt(jsonObject.getInt("x"));
                    serializedMessage.putInt(jsonObject.getInt("y"));
                    serializedMessage.putShort((short)jsonObject.getInt("width"));
                    serializedMessage.putShort((short)jsonObject.getInt("height"));
                    serializedMessage.putInt(jsonObject.getInt("hscroll"));
                    serializedMessage.putInt(jsonObject.getInt("vscroll"));
                    serializedMessage.reset();
                    return serializedMessage;
                case 8:
                    byte[] clipboard = jsonObject.getString("text").getBytes(StandardCharsets.UTF_8);
                    serializedMessage = ByteBuffer.allocate(clipboard.length+6);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    if (jsonObject.getBoolean("pasteFlag") == true){
                        serializedMessage.put((byte)1);
                    }else{
                        serializedMessage.put((byte)0);
                    }
                    serializedMessage.putInt(clipboard.length);
                    serializedMessage.put(clipboard);
                    serializedMessage.reset();
                    return serializedMessage;
                case 9:
                    serializedMessage = ByteBuffer.allocate(2);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.put((byte)jsonObject.getInt("mode"));
                    serializedMessage.reset();
                    return serializedMessage;
                case 4:
                case 5:
                case 6:
                case 7:
                case 10:
                    serializedMessage = ByteBuffer.allocate(1);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.reset();
                    return serializedMessage;
                case 101:
                    byte[] codecOptions = jsonObject.getString("codecOptions").getBytes(StandardCharsets.UTF_8);
                    byte[] encoderNames = jsonObject.getString("encoderNames").getBytes(StandardCharsets.UTF_8);
                    serializedMessage = ByteBuffer.allocate(36+codecOptions.length+encoderNames.length);
                    serializedMessage.mark();
                    serializedMessage.put((byte)type);
                    serializedMessage.putInt(jsonObject.getInt("bitrate"));
                    serializedMessage.putInt(jsonObject.getInt("maxFps"));
                    serializedMessage.put((byte)jsonObject.getInt("iFrameInterval"));
                    serializedMessage.putShort((short)jsonObject.getInt("width"));
                    serializedMessage.putShort((short)jsonObject.getInt("height"));
                    serializedMessage.putShort((short)jsonObject.getInt("left"));
                    serializedMessage.putShort((short)jsonObject.getInt("top"));
                    serializedMessage.putShort((short)jsonObject.getInt("right"));
                    serializedMessage.putShort((short)jsonObject.getInt("bottom"));
                    if (jsonObject.getBoolean("sendMetaFrame") == true){
                        serializedMessage.put((byte)1);
                    }else{
                        serializedMessage.put((byte)0);
                    }
                    serializedMessage.put((byte)jsonObject.getInt("lockedVideoOrientation"));
                    serializedMessage.putInt(jsonObject.getInt("displayId"));
                    if (codecOptions.length > 0) {
                        serializedMessage.putInt(codecOptions.length);
                        serializedMessage.put(codecOptions);
                    }
                    if (encoderNames.length > 0){
                        serializedMessage.putInt(encoderNames.length);
                        serializedMessage.put(encoderNames);
                    }
                    serializedMessage.reset();
                    return serializedMessage;
                case 102:
                    Ln.w("pushFile is currently unavailable");
                    break;
            }
        }catch(JSONException e){
            Ln.w("invalid json format");
        }
        return null;
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteBuffer message) {
        Ln.d("SIMON SAYS : HE GOT A NEW MESSAGE");
        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo == null) {
            Ln.e("No info attached to connection");
            return;
        }
        WebSocketConnection connection = socketInfo.getConnection();
        String address = webSocket.getRemoteSocketAddress().getAddress().getHostAddress();
        Ln.d("SIMON SAYS : attempt to parse event");
        ControlMessage controlMessage = reader.parseEvent(message);
        Ln.d("SIMON SAYS : Client from " + address + " sends bytes: " + message + "msg type : " + controlMessage.getType());
        if (controlMessage != null) {
            Ln.d("SIMON SAYS : processing msg");
            if (controlMessage.getType() == ControlMessage.TYPE_PUSH_FILE) {
                Ln.d("SIMON SAYS : to push file");
                FilePushHandler.handlePush(webSocket, controlMessage);
                return;
            }
            Ln.d("SIMON SAYS : he doesnt want to push file");
            if (controlMessage.getType() == ControlMessage.TYPE_CHANGE_STREAM_PARAMETERS) {
                Ln.d("SIMON SAYS : to change stream parameters");
                VideoSettings videoSettings = controlMessage.getVideoSettings();
                int displayId = videoSettings.getDisplayId();
                if (connection != null) {
                    Ln.d("SIMON SAYS : get old connection");
                    if (connection.getVideoSettings().getDisplayId() != displayId) {
                        connection.leave(webSocket);
                    }
                }
                Ln.i(videoSettings.toString());                
                joinStreamForDisplayId(webSocket, videoSettings, options, displayId, this);
                return;
            }
            Ln.d("SIMON SAYS : he doesnt want to change stream parameters");
            if (connection != null) {
                Ln.d("SIMON SAYS : getting a controller");
                Controller controller = connection.getController();
                controller.handleEvent(controlMessage);
            }
        } else {
            Ln.d("SIMON SAYS : that he doesnt get it");
            Ln.w("Client from " + address + " sends bytes: " + message);
        }
    }

    @Override
    public void onError(WebSocket webSocket, Exception ex) {
        Ln.e("WebSocket error", ex);
        if (webSocket != null) {
            // some errors like port binding failed may not be assignable to a specific websocket
            FilePushHandler.cancelAllForConnection(webSocket);
        }
        if (ex instanceof BindException) {
            System.exit(1);
        }
    }

    @Override
    public void onStart() {
        Ln.d("Server started! " + this.getAddress().toString());
        this.setConnectionLostTimeout(0);
        this.setConnectionLostTimeout(100);
        writePidFile();
    }

    private static void joinStreamForDisplayId(
            WebSocket webSocket, VideoSettings videoSettings, Options options, int displayId, WSServer wsServer) {
        SocketInfo socketInfo = webSocket.getAttachment();
        WebSocketConnection connection = STREAM_BY_DISPLAY_ID.get(displayId);
        if (connection == null) {
            Ln.d("SIMON SAYS : start streaming");
            connection = new WebSocketConnection(options, videoSettings, wsServer);
            STREAM_BY_DISPLAY_ID.put(displayId, connection);
        }
        socketInfo.setConnection(connection);
        connection.join(webSocket, videoSettings);
    }

    private static void unlinkPidFile() {
        try {
            File pidFile = new File(PID_FILE_PATH);
            if (pidFile.exists()) {
                if (!pidFile.delete()) {
                    Ln.e("Failed to delete PID file");
                }
            }
        } catch (Exception e) {
            Ln.e("Failed to delete PID file:", e);
        }
    }

    private static void writePidFile() {
        Ln.d("SIMON SAYS : Writing Pid File");
        File file = new File(PID_FILE_PATH);
        FileOutputStream stream;
        try {
            stream = new FileOutputStream(file, false);
            stream.write(Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.UTF_8));
            Ln.d("SIMON SAYS : Pid File is written");
            stream.close();
        } catch (IOException e) {
            Ln.e(e.getMessage());
        }
    }

    public static WebSocketConnection getConnectionForDisplay(int displayId) {
        return STREAM_BY_DISPLAY_ID.get(displayId);
    }

    public static void releaseConnectionForDisplay(int displayId) {
        STREAM_BY_DISPLAY_ID.remove(displayId);
    }

    public void sendInitialInfoToAll() {
        Collection<WebSocket> webSockets = this.getConnections();
        if (webSockets.isEmpty()) {
            return;
        }
        ByteBuffer initialInfo = WebSocketConnection.getInitialInfo();
        for (WebSocket webSocket : webSockets) {
            SocketInfo socketInfo = webSocket.getAttachment();
            if (socketInfo == null) {
                continue;
            }
            WebSocketConnection.sendInitialInfo(initialInfo, webSocket, socketInfo.getId());
        }
    }
}
