package com.genymobile.scrcpy;

import android.media.MediaCodecInfo;

import org.java_websocket.WebSocket;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


//ADDED FROM WS-SCRCPY : If its initialized with websocket, connects with websocket.
public class WebSocketConnection extends Connection {
    private static final byte[] MAGIC_BYTES_INITIAL = "scrcpy_initial".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAGIC_BYTES_MESSAGE = "scrcpy_message".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DEVICE_NAME_BYTES = Device.getDeviceName().getBytes(StandardCharsets.UTF_8);
    private final WSServer wsServer;
    private final HashSet<WebSocket> sockets = new HashSet<>();
    private ScreenEncoder screenEncoder;

    //initialize
    public WebSocketConnection(Options options, VideoSettings videoSettings, WSServer wsServer) {
        super(options, videoSettings);
        this.wsServer = wsServer;
    }
 
    //start encoding
    public void join(WebSocket webSocket, VideoSettings videoSettings) {
        sockets.add(webSocket);
        boolean changed = setVideoSettings(videoSettings);
        wsServer.sendInitialInfoToAll();
        if (!Device.isScreenOn()) {
            controller.turnScreenOn();
        }
        if (screenEncoder == null || !screenEncoder.isAlive()) {
            Ln.d("First connection. Start new encoder.");
            device.setRotationListener(this);
            screenEncoder = new ScreenEncoder(videoSettings);
            screenEncoder.start(device, this);
        } else {
            if (!changed) {
                if (this.streamInvalidateListener != null) {
                    streamInvalidateListener.onStreamInvalidate();
                }
            }
        }
    }

    public void leave(WebSocket webSocket) {
        sockets.remove(webSocket);
        if (sockets.isEmpty()) {
            Ln.d("Last client has left");
            this.release();
        }
        wsServer.sendInitialInfoToAll();
    }

    public static ByteBuffer deviceMessageToByteBuffer(DeviceMessage msg) {
        ByteBuffer buffer = ByteBuffer.wrap(msg.writeToByteArray(MAGIC_BYTES_MESSAGE.length));
        buffer.put(MAGIC_BYTES_MESSAGE);
        buffer.rewind();
        return buffer;
    }

    @Override
    void send(ByteBuffer data) {
        if (sockets.isEmpty()) {
            return;
        }
        synchronized (sockets) {
            for (WebSocket webSocket : sockets) {
                WSServer.SocketInfo info = webSocket.getAttachment();
                if (!webSocket.isOpen() || info == null) {
                    continue;
                }
                webSocket.send(data);
            }
        }
    }

    public static void sendInitialInfo(ByteBuffer initialInfo, WebSocket webSocket, int clientId) {
        // initialInfo.position(initialInfo.capacity() - 4);
        // initialInfo.putInt(clientId);
        initialInfo.rewind();
        try{
            JSONObject JSONInitialInfo = new JSONObject();
            JSONArray data = new JSONArray();
            byte[] rawHeader = new byte["scrcpy_initial".length()];
            byte[] rawDeviceName = new byte[64];
            initialInfo.get(rawHeader);
            initialInfo.get(rawDeviceName);
            String header = new String(rawHeader);
            JSONInitialInfo.put("header",header);
            String deviceName = new String(rawDeviceName);
            JSONInitialInfo.put("deviceName",deviceName.replace("\0", ""));
            int displaysCount = initialInfo.getInt();
            for (int i=0; i<displaysCount; i++){
                JSONObject singleInfo = new JSONObject();
                JSONObject displayInfo = new JSONObject();
                JSONObject screenInfo = new JSONObject();
                JSONObject videoSettings = new JSONObject();
                
                byte[] rawDisplayInfo = new byte[24];
                initialInfo.get(rawDisplayInfo);
                ByteBuffer buffDisplayInfo = ByteBuffer.wrap(rawDisplayInfo);
                displayInfo.put("displayId", buffDisplayInfo.getInt());
                displayInfo.put("width", buffDisplayInfo.getInt());
                displayInfo.put("heightId", buffDisplayInfo.getInt());
                displayInfo.put("rotationId", buffDisplayInfo.getInt());
                displayInfo.put("layerStack", buffDisplayInfo.getInt());
                displayInfo.put("flags", buffDisplayInfo.getInt());
                singleInfo.put("displayInfo", displayInfo);
                
                int screenInfoLength = initialInfo.getInt();
                if (screenInfoLength>0){
                    byte[] rawScreenInfo = new byte[screenInfoLength];
                    ByteBuffer buffScrenInfo = ByteBuffer.wrap(rawScreenInfo);
                    screenInfo.put("left", buffScrenInfo.getInt());
                    screenInfo.put("top", buffScrenInfo.getInt());
                    screenInfo.put("right", buffScrenInfo.getInt());
                    screenInfo.put("bottom", buffScrenInfo.getInt());
                    screenInfo.put("width", buffScrenInfo.getInt());
                    screenInfo.put("height", buffScrenInfo.getInt());
                    screenInfo.put("rotation", (int)(buffScrenInfo.getInt()&0xffffffffL));
                    singleInfo.put("screenInfo",screenInfo);
                }
                int videoSettingsLength = initialInfo.getInt();
                if (videoSettingsLength>0){
                    byte[] rawVideoSettings = new byte[videoSettingsLength];
                    ByteBuffer buffVideoSettings = ByteBuffer.wrap(rawVideoSettings);
                    videoSettings.put("bitrate", buffVideoSettings.getInt());
                    videoSettings.put("maxFps", buffVideoSettings.getInt());
                    videoSettings.put("iFrameInterval", (int)buffVideoSettings.get());
                    videoSettings.put("width", (int)buffVideoSettings.getShort());
                    videoSettings.put("height", (int)buffVideoSettings.getShort());
                    videoSettings.put("left", (int)buffVideoSettings.getShort());
                    videoSettings.put("top", (int)buffVideoSettings.getShort());
                    videoSettings.put("right", (int)buffVideoSettings.getShort());
                    videoSettings.put("bottom", (int)buffVideoSettings.getShort());
                    videoSettings.put("sendFrameData", (int)buffVideoSettings.get());
                    videoSettings.put("lockedVideoOrientation", (int)buffVideoSettings.get());
                    videoSettings.put("displayId", buffVideoSettings.getInt());
                    int codecOptionLength = buffVideoSettings.getInt();
                    if (codecOptionLength>0){
                        byte[] rawCodecOption = new byte[codecOptionLength];
                        buffVideoSettings.get(rawCodecOption);
                        String codecOption = new String(rawCodecOption);
                        videoSettings.put("codecOption", codecOption);
                    }
                    int encoderNameLength = buffVideoSettings.getInt();
                    if (encoderNameLength>0){
                        byte[] rawEncoderNames = new byte[encoderNameLength];
                        buffVideoSettings.get(rawEncoderNames);
                        String encoderNames = new String(rawEncoderNames);
                        videoSettings.put("encoderNames", encoderNames);
                    }
                    singleInfo.put("videoSettings",videoSettings);
                }
                data.put(singleInfo);
            }
            JSONInitialInfo.put("data", data);
            Ln.d("JSON SAYS : " + JSONInitialInfo.toString());    
            webSocket.send(JSONInitialInfo.toString());
        }catch(Exception e){

        }
    }

    public void sendDeviceMessage(DeviceMessage msg) {
        ByteBuffer buffer = deviceMessageToByteBuffer(msg);
        send(buffer);
    }

    @Override
    public boolean hasConnections() {
        return sockets.size() > 0;
    }

    @Override
    public void close() throws Exception {
//        wsServer.stop();
    }

    public VideoSettings getVideoSettings() {
        return videoSettings;
    }

    public Controller getController() {
        return controller;
    }

    public Device getDevice() {
        return device;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public static ByteBuffer getInitialInfo() {
        int baseLength = MAGIC_BYTES_INITIAL.length
                + DEVICE_NAME_FIELD_LENGTH
                + 4                          // displays count
                + 4;                         // client id
        int additionalLength = 0;
        int[] displayIds = Device.getDisplayIds();
        List<DisplayInfo> displayInfoList = new ArrayList<>();
        HashMap<Integer, Integer> connectionsCount = new HashMap<>();
        HashMap<Integer, byte[]> displayInfoMap = new HashMap<>();
        HashMap<Integer, byte[]> videoSettingsBytesMap = new HashMap<>();
        HashMap<Integer, byte[]> screenInfoBytesMap = new HashMap<>();

        for (int displayId : displayIds) {
            DisplayInfo displayInfo = Device.getDisplayInfo(displayId);
            displayInfoList.add(displayId, displayInfo);
            byte[] displayInfoBytes = displayInfo.toByteArray();
            additionalLength += displayInfoBytes.length;
            displayInfoMap.put(displayId, displayInfoBytes);
            WebSocketConnection connection = WSServer.getConnectionForDisplay(displayId);
            additionalLength += 4; // for connection.connections.size()
            additionalLength += 4; // for screenInfoBytes.length
            additionalLength += 4; // for videoSettingsBytes.length
            if (connection != null) {
                connectionsCount.put(displayId, connection.sockets.size());
                byte[] screenInfoBytes = connection.getDevice().getScreenInfo().toByteArray();
                additionalLength += screenInfoBytes.length;
                screenInfoBytesMap.put(displayId, screenInfoBytes);
                byte[] videoSettingsBytes = connection.getVideoSettings().toByteArray();
                additionalLength += videoSettingsBytes.length;
                videoSettingsBytesMap.put(displayId, videoSettingsBytes);
            }
        }

        MediaCodecInfo[] encoders = ScreenEncoder.listEncoders();
        List<byte[]> encodersNames = new ArrayList<>();
        if (encoders != null && encoders.length > 0) {
            additionalLength += 4;
            for (MediaCodecInfo encoder : encoders) {
                byte[] nameBytes = encoder.getName().getBytes(StandardCharsets.UTF_8);
                additionalLength += 4 + nameBytes.length;
                encodersNames.add(nameBytes);
            }
        }

        byte[] fullBytes = new byte[baseLength + additionalLength];
        ByteBuffer initialInfo = ByteBuffer.wrap(fullBytes);
        initialInfo.put(MAGIC_BYTES_INITIAL);
        initialInfo.put(DEVICE_NAME_BYTES, 0, Math.min(DEVICE_NAME_FIELD_LENGTH - 1, DEVICE_NAME_BYTES.length));
        initialInfo.position(MAGIC_BYTES_INITIAL.length + DEVICE_NAME_FIELD_LENGTH);
        initialInfo.putInt(displayIds.length);
        for (DisplayInfo displayInfo : displayInfoList) {
            int displayId = displayInfo.getDisplayId();
            if (displayInfoMap.containsKey(displayId)) {
                initialInfo.put(displayInfoMap.get(displayId));
            }
            int count = 0;
            if (connectionsCount.containsKey(displayId)) {
                count = connectionsCount.get(displayId);
            }
            initialInfo.putInt(count);
            if (screenInfoBytesMap.containsKey(displayId)) {
                byte[] screenInfo = screenInfoBytesMap.get(displayId);
                initialInfo.putInt(screenInfo.length);
                initialInfo.put(screenInfo);
            } else {
                initialInfo.putInt(0);
            }
            if (videoSettingsBytesMap.containsKey(displayId)) {
                byte[] videoSettings = videoSettingsBytesMap.get(displayId);
                initialInfo.putInt(videoSettings.length);
                initialInfo.put(videoSettings);
            } else {
                initialInfo.putInt(0);
            }
        }
        initialInfo.putInt(encodersNames.size());
        for (byte[] encoderNameBytes : encodersNames) {
            initialInfo.putInt(encoderNameBytes.length);
            initialInfo.put(encoderNameBytes);
        }

        return initialInfo;
    }

    public void onRotationChanged(int rotation) {
        super.onRotationChanged(rotation);
        wsServer.sendInitialInfoToAll();
    }

    private void release() {
        WSServer.releaseConnectionForDisplay(this.videoSettings.getDisplayId());
        // encoder will stop itself after checking .hasConnections()
    }
}
