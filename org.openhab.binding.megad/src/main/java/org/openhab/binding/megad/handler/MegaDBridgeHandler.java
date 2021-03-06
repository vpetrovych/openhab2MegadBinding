package org.openhab.binding.megad.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.megad.MegaDBindingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MegaDBridgeHandler} is responsible for bridge.
 *
 * This is server for incoming connections from megad
 *
 * @author Petr Shatsillo - Initial contribution
 */

public class MegaDBridgeHandler extends BaseBridgeHandler {

    private Logger logger = LoggerFactory.getLogger(MegaDBridgeHandler.class);

    private boolean isConnect = false;
    private int port;
    private ScheduledFuture<?> pollingJob;
    Socket s = null;
    private ServerSocket ss;
    private InputStream is;
    private OutputStream os;
    private boolean isRunning = true;
    private int refreshInterval = 300;
    MegaDHandler megaDHandler;

    public MegaDBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO Auto-generated method stub

    }

    @Override
    public void updateStatus(ThingStatus status) {
        super.updateStatus(status);
        updateThingHandlersStatus(status);

    }

    public void updateStatus() {
        if (isConnect) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE);
        }

    }

    private Map<String, MegaDHandler> thingHandlerMap = new HashMap<String, MegaDHandler>();

    public void registerMegadThingListener(MegaDHandler thingHandler) {
        if (thingHandler == null) {
            throw new IllegalArgumentException("It's not allowed to pass a null ThingHandler.");
        } else {

            String thingID = thingHandler.getThing().getConfiguration().get("hostname").toString() + "."
                    + thingHandler.getThing().getConfiguration().get("port").toString();

            if (thingHandlerMap.get(thingID) != null) {
                thingHandlerMap.remove(thingID);
            }
            logger.debug("thingHandler for thing: '{}'", thingID);
            if (thingHandlerMap.get(thingID) == null) {
                thingHandlerMap.put(thingID, thingHandler);
                logger.debug("register thingHandler for thing: {}", thingHandler);
                updateThingHandlerStatus(thingHandler, this.getStatus());
                if (thingID.equals("localhost.")) {
                    updateThingHandlerStatus(thingHandler, ThingStatus.OFFLINE);
                }
                // sendSocketData("get " + thingID);
            } else {
                logger.debug("thingHandler for thing: '{}' already registerd", thingID);
                updateThingHandlerStatus(thingHandler, this.getStatus());
            }

        }
    }

    public void unregisterThingListener(MegaDHandler thingHandler) {
        if (thingHandler != null) {
            String thingID = thingHandler.getThing().getConfiguration().get("hostname").toString() + "."
                    + thingHandler.getThing().getConfiguration().get("port").toString();
            if (thingHandlerMap.remove(thingID) == null) {
                logger.debug("thingHandler for thing: {} not registered", thingID);
            } else {
                updateThingHandlerStatus(thingHandler, ThingStatus.OFFLINE);
            }
        }

    }

    private void updateThingHandlerStatus(MegaDHandler thingHandler, ThingStatus status) {
        thingHandler.updateStatus(status);
    }

    private void updateThingHandlersStatus(ThingStatus status) {
        for (Map.Entry<String, MegaDHandler> entry : thingHandlerMap.entrySet()) {
            updateThingHandlerStatus(entry.getValue(), status);
        }
    }

    public ThingStatus getStatus() {
        return getThing().getStatus();
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Megad bridge handler {}", this.toString());

        MegaDBindingConfiguration configuration = getConfigAs(MegaDBindingConfiguration.class);
        port = configuration.port;

        // updateStatus(ThingStatus.ONLINE);

        startBackgroundService();
    }

    private void startBackgroundService() {
        logger.debug("Starting background service...");
        if (pollingJob == null || pollingJob.isCancelled()) {
            pollingJob = scheduler.scheduleAtFixedRate(pollingRunnable, 0, refreshInterval, TimeUnit.SECONDS);
        }
    }

    private Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            logger.debug("Polling job called");
            try {
                ss = new ServerSocket(port);
                logger.info("MegaD bridge opened port {}", ss.getLocalPort());
                isRunning = true;
                updateStatus(ThingStatus.ONLINE);
            } catch (IOException e) {
                logger.error("ERROR! Cannot open port: {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE);
            }

            while (isRunning) {
                try {
                    s = ss != null ? ss.accept() : null;
                } catch (IOException e) {
                    logger.error("ERROR in bridge. Incoming server has error: {}", e.getMessage());
                }
                if (!ss.isClosed()) {
                    new Thread(startHttpSocket());
                }
            }
        }

    };

    protected Runnable startHttpSocket() {
        try {
            this.is = s.getInputStream();
            this.os = s.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        String input = readInput();
        writeResponse();
        ParseInput(input);
        return null;
    }

    private String readInput() {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String string = "";
        try {
            string = br.readLine();
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        }

        return string;
    }

    private void ParseInput(String s) {
        String[] getCommands;
        String thingID, hostAddress;

        if (!(s == null || s.trim().length() == 0)) {

            if (s.contains("GET") && s.contains("?")) {
                logger.debug("incoming from Megad: {} {}", this.s.getInetAddress().getHostAddress(), s);
                String[] CommandParse = s.split("[/ ]");
                String command = CommandParse[2];
                getCommands = command.split("[?&>=]");

                for (int i = 0; getCommands.length > i; i++) {
                    logger.debug("{} value {}", i, getCommands[i]);
                }

                if (s.contains("m=1")) {
                    hostAddress = this.s.getInetAddress().getHostAddress();
                    if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                        hostAddress = "localhost";
                    }
                    thingID = hostAddress + "." + getCommands[2];
                    megaDHandler = thingHandlerMap.get(thingID);
                    if (megaDHandler != null) {
                        megaDHandler.updateValues(hostAddress, getCommands, OnOffType.OFF);
                    }
                } else if (s.contains("m=2")) {
                    // do nothing -- long pressed
                } else if (s.contains("all=")) {
                    logger.debug("Loop incoming from Megad: {} {}", this.s.getInetAddress().getHostAddress(), s);

                    getCommands = s.split("[= ]");
                    if (getCommands.length > 4) {
                        String[] parsedStatus = getCommands[3].split("[;]");
                        for (int i = 0; parsedStatus.length > i; i++) {
                            String[] mode = parsedStatus[i].split("[/]");
                            if (mode[0].contains("ON")) {
                                hostAddress = this.s.getInetAddress().getHostAddress();
                                if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                                    hostAddress = "localhost";
                                }
                                thingID = hostAddress + "." + i;
                                megaDHandler = thingHandlerMap.get(thingID);
                                if (megaDHandler != null) {
                                    logger.debug("Updating: {} Value is: {}", thingID, mode);
                                    megaDHandler.updateValues(hostAddress, parsedStatus, OnOffType.ON);
                                }
                            } else if (mode[0].contains("OFF")) {
                                hostAddress = this.s.getInetAddress().getHostAddress();
                                if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                                    hostAddress = "localhost";
                                }
                                thingID = hostAddress + "." + i;
                                megaDHandler = thingHandlerMap.get(thingID);
                                if (megaDHandler != null) {
                                    logger.debug("Updating: {} Value is: {}", thingID, mode);
                                    megaDHandler.updateValues(hostAddress, parsedStatus, OnOffType.OFF);
                                }
                            } else {
                                logger.debug("Not a switch");
                            }
                        }
                    } else {
                        String[] parsedStatus = getCommands[2].split("[;]");
                        for (int i = 0; parsedStatus.length > i; i++) {
                            String[] mode = parsedStatus[i].split("[/]");
                            if (mode[0].contains("ON")) {
                                hostAddress = this.s.getInetAddress().getHostAddress();
                                if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                                    hostAddress = "localhost";
                                }
                                thingID = hostAddress + "." + i;
                                megaDHandler = thingHandlerMap.get(thingID);
                                if (megaDHandler != null) {
                                    logger.debug("Updating: {} Value is: {}", thingID, parsedStatus[i]);
                                    megaDHandler.updateValues(hostAddress, mode, OnOffType.ON);
                                }
                            } else if (mode[0].contains("OFF")) {
                                hostAddress = this.s.getInetAddress().getHostAddress();
                                if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                                    hostAddress = "localhost";
                                }
                                thingID = hostAddress + "." + i;
                                megaDHandler = thingHandlerMap.get(thingID);
                                if (megaDHandler != null) {
                                    logger.debug("Updating: {} Value is: {}", thingID, mode);
                                    megaDHandler.updateValues(hostAddress, mode, OnOffType.OFF);
                                }
                            } else {
                                logger.debug("Not a switch");
                            }
                        }
                    }
                } else {
                    hostAddress = this.s.getInetAddress().getHostAddress();
                    if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
                        hostAddress = "localhost";
                    }
                    if (getCommands[1].equals("pt")) {
                        thingID = hostAddress + "." + getCommands[2];
                    } else if (getCommands[1].equals("st")) {

                        thingID = hostAddress;
                        logger.debug("" + thingHandlerMap.size());

                        for (int i = 0; thingHandlerMap.size() > i; i++) {

                            if (thingHandlerMap.keySet().toArray()[i].toString().contains(hostAddress)) {
                                megaDHandler = thingHandlerMap.get(thingHandlerMap.keySet().toArray()[i].toString());
                                if (megaDHandler != null) {
                                    megaDHandler.updateValues(hostAddress, getCommands, OnOffType.ON);
                                }
                            }
                        }

                    } else {
                        thingID = hostAddress + "." + getCommands[1];
                    }
                    megaDHandler = thingHandlerMap.get(thingID);
                    if (megaDHandler != null) {
                        megaDHandler.updateValues(hostAddress, getCommands, OnOffType.ON);
                    }
                }

            }
        }
    }

    private void writeResponse() {
        String result = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html\r\n" + "Content-Length: " + 0 + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            os.write(result.getBytes());
            is.close();
            os.close();
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    @Override
    public void dispose() {
        logger.debug("Dispose Megad bridge handler {}", this.toString());

        if (pollingJob != null && !pollingJob.isCancelled()) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
        isRunning = false;
        try {
            ss.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        updateStatus(ThingStatus.OFFLINE); // Set all State to offline
    }
}
