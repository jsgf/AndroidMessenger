/*------------------------------------------------------------------------------
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Original Code is levelonelabs.com code.
 * The Initial Developer of the Original Code is Level One Labs. Portions
 * created by the Initial Developer are Copyright (C) 2001 the Initial
 * Developer. All Rights Reserved.
 *
 *         Contributor(s):
 *             Scott Oster      (ostersc@alum.rpi.edu)
 *             Steve Zingelwicz (sez@po.cwru.edu)
 *             William Gorman   (willgorman@hotmail.com)
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable
 * instead of those above. If you wish to allow use of your version of this
 * file only under the terms of either the GPL or the LGPL, and not to allow
 * others to use your version of this file under the terms of the NPL, indicate
 * your decision by deleting the provisions above and replace them with the
 * notice and other provisions required by the GPL or the LGPL. If you do not
 * delete the provisions above, a recipient may use your version of this file
 * under the terms of any one of the NPL, the GPL or the LGPL.
 *----------------------------------------------------------------------------*/

package com.levelonelabs.aim;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;


/**
 * Implements the AIM protocol
 * 
 * @author Scott Oster, Will Gorman
 * @created September 4, 2001
 */
public class AIMClient implements Runnable, AIMSender {
    // check connection ever "TIME_DELAY" milliseconds (5 mins)
    private static final long TIME_DELAY = 5 * 60 * 1000;
    private static final String PING = "PING";

    private AimConnectionCheck watchdogCheck;
    private AimConnectionCheck watchdogVerify;
    boolean connectionVerified = false;
    private Timer connectionCheck = new Timer();

    static Logger logger = Logger.getLogger(AIMClient.class.getName());

    // rate limiting
    private static final int MAX_POINTS = 10;

    private static final int RECOVER_RATE = 2200;

    // for TOC3 (using toc2_login)
    // private static final String REVISION = "\"TIC:\\Revision: 1.61 \" 160 US
    // \"\" \"\" 3 0 30303 -kentucky -utf8 94791632";
    private static final String REVISION = "\"TIC:TOC2\" 160";

    private String loginServer = "toc.oscar.aol.com";

    private int loginPort = 5190;

    private String authorizerServer = "login.oscar.aol.com";

    private int authorizerPort = 29999;

    private List aimListeners = new ArrayList();

    String name;

    private String pass;

    private String info;

    private String nonUserResponse;

    boolean online;

    private boolean autoAddUsers = false;

    // private final String ROAST = "Tic/Toc";
    private int seqNo;

    private Socket connection;

    private DataInputStream in;

    private DataOutputStream out;

    private Map<String,AIMBuddy> buddyHash;

    private int sendLimit = MAX_POINTS;

    private long lastFrameSendTime = System.currentTimeMillis();

    private int permitMode = PERMIT_ALL;

    private Set permitted;

    private Set denied;


    /**
     * Constructor for the AIMClient object
     * 
     * @param name
     * @param pass
     * @param info
     *            Description of the Parameter
     * @param response
     *            what to say to non-users when they message the bot (if
     *            autoaddUsers==false)
     * @param autoAddUsers
     */
    public AIMClient(String name, String pass, String info, String response, boolean autoAddUsers) {
        this.nonUserResponse = response;

        buddyHash = new HashMap<String,AIMBuddy>();
        permitted = new HashSet();
        denied = new HashSet();
        this.name = imNormalize(name);
        this.pass = pass;
        this.info = info;
        this.autoAddUsers = autoAddUsers;
        this.addBuddy(new AIMBuddy(name));
    }


    /**
     * Constructor for the AIMClient object
     * 
     * @param name
     * @param pass
     * @param info
     *            Description of the Parameter
     * @param autoAddUsers
     */
    public AIMClient(String name, String pass, String info, boolean autoAddUsers) {
        this(name, pass, info, "Sorry, you must be a user of this system to send requests.", autoAddUsers);
    }


    /**
     * Constructor for the AIMClient object
     * 
     * @param name
     * @param pass
     * @param info
     *            Description of the Parameter
     */
    public AIMClient(String name, String pass, String info) {
        this(name, pass, info, false);
    }


    /**
     * Constructor for the AIMClient object
     * 
     * @param name
     * @param pass
     */
    public AIMClient(String name, String pass) {
        this(name, pass, "No info", false);
    }


    /**
     * Strip out HTML from a string
     * 
     * @param line * *
     * @return the string without HTML
     */
    public static String stripHTML(String line) {
        StringBuffer sb = new StringBuffer(line);
        String out = "";

        for (int i = 0; i < (sb.length() - 1); i++) {
            if (sb.charAt(i) == '<') {
                // Most tags
                if ((sb.charAt(i + 1) == '/') || ((sb.charAt(i + 1) >= 'a') && (sb.charAt(i + 1) <= 'z'))
                    || ((sb.charAt(i + 1) >= 'A') && (sb.charAt(i + 1) <= 'Z'))) {
                    for (int j = i + 1; j < sb.length(); j++) {
                        if (sb.charAt(j) == '>') {
                            sb = sb.replace(i, j + 1, "");
                            i--;
                            break;
                        }
                    }
                } else if (sb.charAt(i + 1) == '!') {
                    // Comments
                    for (int j = i + 1; j < sb.length(); j++) {
                        if ((sb.charAt(j) == '>') && (sb.charAt(j - 1) == '-') && (sb.charAt(j - 2) == '-')) {
                            sb = sb.replace(i, j + 1, "");
                            i--;
                            break;
                        }
                    }
                }
            }
        }

        out = sb.toString();
        return out;
    }


    /**
     * Protocol method
     * 
     * @para * *
     * @return roasted string
     */
    private static String imRoast(String pass) {
        String roast = "Tic/Toc";
        String out = "";
        String in = pass;
        String out2 = "0x";
        for (int i = 0; i < in.length(); i++) {
            out = java.lang.Long.toHexString(in.charAt(i) ^ roast.charAt(i % 7));
            if (out.length() < 2) {
                out2 = out2 + "0";
            }

            out2 = out2 + out;
        }

        return out2;
    }


    /**
     * Protocol method * in
     * 
     * @return normalized string
     */
    private static String imNormalize(String in) {
        String out = "";
        in = in.toLowerCase();
        char[] arr = in.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != ' ') {
                out = out + "" + arr[i];
            }
        }

        return out;
    }


    /**
     * Retrieve a buddy from the list
     * 
     * @param buddyName
     * @return The buddy
     */
    public AIMBuddy getBuddy(String buddyName) {
        return (AIMBuddy) buddyHash.get(imNormalize(buddyName));
    }


    /**
     * Get an iterator for all the current buddy names
     * 
     * @return iterator
     */
    public Iterator getBuddyNames() {
        return Arrays.asList(buddyHash.keySet().toArray()).iterator();
    }


    /**
     * Sign on to aim server
     */
    public void signOn() {
        new Thread(this).start();

        // check the connection
        watchdogCheck = new AimConnectionCheck(this, true);

        // verify the message was received 5 secs later
        watchdogVerify = new AimConnectionCheck(this, false);
        connectionCheck.scheduleAtFixedRate(watchdogCheck, TIME_DELAY, TIME_DELAY);
        connectionCheck.scheduleAtFixedRate(watchdogVerify, TIME_DELAY + 5000, TIME_DELAY);

        // give the server time to log us on before returning flow to the user
        // check for success once every 2 secs, up to 20 secs
        // true connection comes from the handledConnected call back
        for (int i = 0; i < 10; i++) {
            if (!this.online) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                return;
            }
        }
    }


    /**
     * Sign off from aim server
     */
    public void signOff() {
        // cancel the ping until signon is called again
    	if(watchdogCheck != null)
    		watchdogCheck.cancel();
    	if(watchdogVerify != null)
    		watchdogVerify.cancel();
        signoff("User request");
    }

    /**
     * A hack to get around skip throwing IOExceptions.. anrdoid still is kinda broken
     * @throws IOException 
     */
    public void skipBytes(DataInputStream i, int bytes) throws IOException{
    	for(int x=0; x<bytes; x++)
    		i.readByte();
    }

    /**
     * Main processing method for the AIMClient object
     */
    public void run() {
        int length;
        seqNo = (int) Math.floor(Math.random() * 65535.0);

        // AOL likes to have a bunch of bogus IPs for some reason, so lets try
        // them all until one works
        //Problem is for some reason this crashes on android!  Commenting the string array going to just use 1 string
        //InetAddress[] loginIPs = null;
        InetAddress loginIP = null;
        try {
            //loginIPs = InetAddress.getAllByName(loginServer);
        	loginIP = InetAddress.getByName(loginServer);
        } catch (UnknownHostException e) {
            signoff("0");
            generateError("Signon err", e.getMessage());
            return;
        } catch (OutOfMemoryError e) {
        	
        }

     //   for (int i = 0; i < loginIPs.length; i++) {
            try {
              //  logger.info("Attempting to logon using IP:" + loginIPs[i]);
                logger.info("Attempting to logon using IP:" + loginIP);
                // * Client connects to TOC
                //connection = new Socket(loginIPs[i], loginPort);
                connection = new Socket(loginIP, loginPort);
                connection.setSoTimeout(10000);
                in = new DataInputStream(connection.getInputStream());
                out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(),512));
               // logger.info("Successfully connected using IP:" + loginIPs[i]);
                logger.info("Successfully connected using IP:" + loginIP);
               // break;
            } catch (Exception e) {
                // try the next one
            }
 //       }

        if (connection == null || in == null || out == null) {
            signoff("1");
            generateError("Signon err", "Unable to establish connection to logon server.");
            return;
        }

        logger.info("*** Starting AIM CLIENT (SEQNO:" + seqNo + ") ***");
        try {
            // * Client sends "FLAPON\r\n\r\n"
            out.writeBytes("FLAPON\r\n\r\n");
            out.flush();
            // 6 byte header, plus 4 FLAP version (1)
            byte[] signon = new byte[10];
            // * TOC sends Client FLAP SIGNON
            in.readFully(signon);
            // * Client sends TOC FLAP SIGNON
            out.writeByte(42);// *
            
            logger.info("FLAP SIGNIN RECIEVED");
            
            
            out.writeByte(1); // SIGNON TYPE
            out.writeShort(seqNo); // SEQ NO
            seqNo = (seqNo + 1) & 65535;
            out.writeShort(name.length() + 8); // data length = username length
            // + SIGNON DATA
            out.writeInt(1); // FLAP VERSION
            out.writeShort(1); // TLF TAG
            out.writeShort(name.length()); // username length
            out.writeBytes(name); // usename
            out.flush();
            logger.info("LOGIN INFO SENT");
            // * Client sends TOC "toc_signon" message
            frameSend("toc2_signon " + authorizerServer + " " + authorizerPort + " " + name + " " + imRoast(pass)
                + " English " + REVISION + " " + toc2MagicNumber(name, pass) + "\0");
            logger.info("FRAME SENT?");
            // * if login fails TOC drops client's connection
            // else TOC sends client SIGN_ON reply
            logger.info("After frame sent, we have # available:" + in.available());
            try{
            	skipBytes(in, 4);
            }catch(IOException e){
            	//this sometimes crashes; not sure?
            	logger.info("SKIP 4 THREW IOEXCEPTION " + e.getMessage());
            }
            logger.info("skipping 4?" + in.available());
            length = in.readShort(); // data length
            logger.info("Tried to read something..." + length + in.available());
            signon = new byte[length];
            in.readFully(signon); // data
            logger.info("Got signon packets, analyzing...");
            if (new String(signon).startsWith("ERROR")) {
                fromAIM(signon);
                logger.severe("Signon error");
                signoff("2");
                return;
            }
            logger.info("LOGIN OK?");
            skipBytes(in, 4); // seq num
            logger.info("Another problem with skip?");
            length = in.readShort(); // data length
            signon = new byte[length];
            in.readFully(signon); // data
            // * Client sends TOC toc_init_done message
            frameSend("toc_init_done\0");
            online = true;
            generateConnected();
            frameSend("toc_set_info \"" + info + "\"\0");
            logger.info("Done with AIM logon");
            connection.setSoTimeout(3000);
        } catch (InterruptedIOException e) {
            signoff("2.25");
        } catch (IOException e) {
            signoff("3");
        }

        byte[] data;
        while (true) {
            try {
            	skipBytes(in, 4);
                length = in.readShort();
                data = new byte[length];
                in.readFully(data);
                fromAIM(data);
                //logger.info("SEQNO:"+seqNo);
            } catch (InterruptedIOException e) {
                // This is normal; read times out when we don't read anything.
                 //logger.warning("*** AIM ERROR: " + e + " ***");
            } catch (IOException e) {
                logger.severe("*** AIM IO ERROR: " + e + "," + e.getMessage() +" ***");
                generateDisconnected();
                break;
            }
        }
       signoff("Connection reset.");
    }


    /**
     * @param name2
     * @param pass2
     * @return
     */
    private static int toc2MagicNumber(String username, String password) {
        int sn = username.charAt(0) - 96;
        int pw = password.charAt(0) - 96;

        int a = sn * 7696 + 738816;
        int b = sn * 746512;
        int c = pw * a;

        return c - a + b + 71665152;
    }


    /**
     * Register a listener to recieve aim events
     * 
     * @param listener
     *            The listener
     */
    public void addAIMListener(AIMListener listener) {
        aimListeners.add(listener);
    }


    /**
     * Send a message to a buddy
     * 
     * @param buddy
     * @param text
     */
    public void sendMessage(AIMBuddy buddy, String text) {
        if ((buddy == null) || buddy.isBanned()) {
            return;
        }

        if (buddy.isOnline()) {
            sendMesg(buddy.getName(), text);
        } else {
            // for some reason we are sending a message to an offline buddy
            // this will generate a status request for them (this message will
            // be lost, but if they are online, we should get an update)
            try {
                frameSend("toc_get_status " + imNormalize(buddy.getName()) + "\0");
            } catch (IOException e) {
                logger.severe("Error sending status request for offline buddy: " + e.getMessage());
            }
        }
    }


    /**
     * Add a single budy
     * 
     * @param buddy
     *            The buddy to add
     */
    public void addBuddy(AIMBuddy buddy) {
        if (buddy == null) {
            return;
        }

        if (getBuddy(buddy.getName()) != null) {
            return;
        }

        if (this.online) {
            String toBeSent = "toc2_new_buddies {g:" + buddy.getGroup() + "\nb:" + imNormalize(buddy.getName()) + "\n}";
            try {
                frameSend(toBeSent + "\0");
            } catch (IOException e) {
                logger.severe(e.toString());
                signoff("Error adding buddy");
            }
        }

        // logger.info("Added buddy to hash");
        buddyHash.put(imNormalize(buddy.getName()), buddy);
    }


    /**
     * Convience method for adding multiple buddies
     * 
     * @param buddyList
     *            List of AIMBuddy
     */
    public void addBuddies(List buddyList) {
        // make a list of buddys for each "group"
        Map groupMap = createGroupMap(buddyList);

        // iterate over the groups and send the buddies
        Iterator groupIter = groupMap.keySet().iterator();
        while (groupIter.hasNext()) {
            String group = (String) groupIter.next();
            String currentlist = "toc2_new_buddies {g:" + group + "\n";
            List groupList = (List) groupMap.get(group);
            for (int i = 0; i < groupList.size(); i++) {
                AIMBuddy buddy = (AIMBuddy) groupList.get(i);
                buddyHash.put(imNormalize(buddy.getName()), buddy);
                currentlist += "b:" + imNormalize(buddy.getName()) + "\n";
                if (currentlist.length() > 1800) {
                    try {
                        frameSend(currentlist + "}\0");
                        currentlist = "toc2_new_buddies {g:" + group + "\n";
                    } catch (IOException e) {
                        e.printStackTrace();
                        logger.severe("ERROR adding buddies.");
                    }
                }
            }
            // send the left overs (if any)
            if (currentlist.length() > ("toc2_new_buddies {g:" + group + "\n").length()) {
                try {
                    frameSend(currentlist + "}\0");
                } catch (IOException e) {
                    e.printStackTrace();
                    logger.severe("ERROR adding buddies.");
                }
            }

        }
    }


    /**
     * Create a Map of List of buddies in the same group
     * 
     * @param buddyList
     *            a list of buddies
     * @return a Map <String, List> keyed with group name with value a list of
     *         buddies in that group
     */
    private Map createGroupMap(List buddyList) {
        // <group name,List of buddy>
        Map groupMap = new HashMap();

        // iterate the buddies and group them by group name
        for (Iterator iter = buddyList.iterator(); iter.hasNext();) {
            Object obj = iter.next();
            if (obj instanceof AIMBuddy) {
                AIMBuddy buddy = (AIMBuddy) obj;
                String group = buddy.getGroup();
                // pull the list of buddies in this buddy's group
                List groupList = (List) groupMap.get(group);
                if (groupList == null) {
                    // first buddy in this group, make a new list
                    groupList = new ArrayList();
                    groupMap.put(group, groupList);
                }
                // add the buddy to the list of buddies in this group
                groupList.add(buddy);
            }
        }
        return groupMap;
    }


    /**
     * Remove a single budy
     * 
     * @param buddy
     *            The buddy to add
     */
    public void removeBuddy(AIMBuddy buddy) {
        if (buddy == null) {
            return;
        }

        if (getBuddy(buddy.getName()) == null) {
            return;
        }

        String buddyname = imNormalize(buddy.getName());

        String toBeSent = "toc2_remove_buddy";
        try {
            frameSend(toBeSent + " " + buddyname + " " + buddy.getGroup() + "\0");
        } catch (IOException e) {
            logger.severe(e.toString());
            signoff("Error removing buddy.");
        }

        // logger.info("Removed buddy from hash");
        buddyHash.remove(imNormalize(buddy.getName()));
    }


    /**
     * Convience method for removing multiple buddies
     * 
     * @param buddyList
     *            List of AIMBuddy
     */
    public void removeBuddies(List buddyList) {
        // make a list of buddys for each "group"
        Map groupMap = createGroupMap(buddyList);

        // iterate over the groups and remove the buddies
        Iterator groupIter = groupMap.keySet().iterator();
        while (groupIter.hasNext()) {
            String group = (String) groupIter.next();
            String currentlist = "toc2_remove_buddy";
            List groupList = (List) groupMap.get(group);
            for (int i = 0; i < groupList.size(); i++) {
                AIMBuddy buddy = (AIMBuddy) groupList.get(i);
                buddyHash.remove(imNormalize(buddy.getName()));
                currentlist += " " + imNormalize(buddy.getName());
                if (currentlist.length() > 1800) {
                    try {
                        frameSend(currentlist + " " + group + "\0");
                        currentlist = "toc2_remove_buddy";
                    } catch (IOException e) {
                        e.printStackTrace();
                        logger.severe("ERROR removing buddies.");
                    }
                }
            }
            // remove the left overs (if any)
            if (currentlist.length() > "toc2_remove_buddy".length()) {
                try {
                    frameSend(currentlist + " " + group + "\0");
                } catch (IOException e) {
                    e.printStackTrace();
                    logger.severe("ERROR adding buddies.");
                }
            }

        }
    }


    /**
     * Warn a buddy
     * 
     * @param buddy
     */
    public void sendWarning(AIMBuddy buddy) {
        if (buddy == null) {
            return;
        }

        logger.info("Attempting to warn: " + buddy.getName() + ".");

        String work = "toc_evil ";
        work = work.concat(imNormalize(buddy.getName()));
        work = work.concat(" norm \0");
        // logger.info(work);
        try {
            frameSend(work);
        } catch (IOException e) {
            signoff("9");
        }
    }


    /**
     * tell aim to ignore a buddy
     * 
     * @param buddy
     */
    public void banBuddy(AIMBuddy buddy) {
        if ((buddy == null) || (buddy.getName().length() == 0)) {
            return;
        }

        if (getBuddy(buddy.getName()) == null) {
            return;
        }
        buddy.setBanned(true);
        sendDeny(imNormalize(buddy.getName()));
    }


    /**
     * tell aim to ignore a buddy
     * 
     * @param buddyname
     */
    private void sendDeny(String buddyname) {
        if (buddyname.length() == 0) {
            logger.info("Attempting to permit all.");
        } else {
            logger.info("Attempting to deny: " + buddyname + ".");
        }

        String toBeSent = "toc2_add_deny";
        try {
            frameSend(toBeSent + " " + buddyname + "\0");
        } catch (IOException e) {
            logger.severe(e.toString());
            signoff("7.75");
        }
    }


    /**
     * tell aim to permit a buddy
     * 
     * @param buddyname
     */
    private void sendPermit(String buddyname) {
        logger.info("Attempting to permit: " + buddyname + ".");

        String toBeSent = "toc2_add_permit";
        try {
            frameSend(toBeSent + " " + buddyname + "\0");
        } catch (IOException e) {
            logger.severe(e.getMessage());
            signoff("7.875");
        }
    }


    /**
     * protocol methods *
     * 
     * @param toBeSent
     * @exception IOException
     *                Description of Exception
     */
    private void frameSend(String toBeSent) throws IOException {
        if (sendLimit < MAX_POINTS) {
            sendLimit += ((System.currentTimeMillis() - lastFrameSendTime) / RECOVER_RATE);
            // never let the limit exceed the max, else this code won't work
            // right
            sendLimit = Math.min(MAX_POINTS, sendLimit);
            if (sendLimit < MAX_POINTS) {
                // sendLimit could be less than 0, this still works properly
                logger.info("Current send limit=" + sendLimit + " out of " + MAX_POINTS);
               /* try {
                    // this will wait for every point below the max
                    int waitAmount = MAX_POINTS - sendLimit;
                    logger.info("Delaying send " + waitAmount + " units");
                    //Thread.sleep(RECOVER_RATE * waitAmount);
                    sendLimit += waitAmount;
                } catch (InterruptedException ie) {
                }*/
            }
        }
        out.writeByte(42); // *
        out.writeByte(2); // DATA
        out.writeShort(seqNo); // SEQ NO
        seqNo = (seqNo + 1) & 65535;
        out.writeShort(toBeSent.length()); // DATA SIZE
        out.writeBytes(toBeSent); // DATA
        out.flush();

        // sending is more expensive the higher our warning level
        // this should decrement between 1 and 10 points (exponentially)
        int warnAmount = getBuddy(this.name).getWarningAmount();
        sendLimit -= (1 + Math.pow((3 * warnAmount) / 100, 2));
        lastFrameSendTime = System.currentTimeMillis();
    }


    /**
     * Send message event to all listeners.
     * 
     * @param from
     * @param request
     */
    private void generateMessage(String from, String request) {
        AIMBuddy aimbud = getBuddy(from);
        if (aimbud == null) {
            if (autoAddUsers) {
                aimbud = new AIMBuddy(from);
                addBuddy(aimbud);
                aimbud.setOnline(true);
            } else {
                logger.info("MESSAGE FROM A NON BUDDY(" + from + ")");
                // only send a response if a non-empty one is configured
                if ((nonUserResponse != null) && !nonUserResponse.equals("")) {
                    sendMesg(from, nonUserResponse);
                }
                return;
            }
        }

        if (aimbud.isBanned()) {
            logger.info("Ignoring message from banned user (" + from + "):" + request);
        } else {
            for (int i = 0; i < aimListeners.size(); i++) {
                try {
                    ((AIMListener) aimListeners.get(i)).handleMessage(aimbud, request);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    /**
     * Send warning event to all listeners.
     * 
     * @param from
     * @param amount
     *            of warning
     */
    private void generateWarning(String from, int amount) {
        AIMBuddy aimbud = getBuddy(from);
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleWarning(aimbud, amount);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send connected event to all listeners.
     */
    private void generateConfigReady(Map<String, AIMBuddy> buddyHash2) {
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleConfigReady(buddyHash2);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    /**
     * Send connected event to all listeners.
     */
    private void generateConnected() {
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleConnected();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send disconnected event to all listeners.
     */
    private void generateDisconnected() {
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleDisconnected();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send error event to all listeners.
     * 
     * @param error
     *            code
     * @param message
     */
    private void generateError(String error, String message) {
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleError(error, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send buddy sign on event to all listeners.
     * 
     * @param buddy
     *            that signed on
     * @param message
     */
    private void generateBuddySignOn(String buddy, String message) {
        AIMBuddy aimbud = getBuddy(buddy);
        if (aimbud == null) {
        	//aimbud = new AIMBuddy(buddy);
        	//addBuddy(aimbud);
            logger.severe("ERROR:  NOTIFICATION ABOUT NON BUDDY SIGN ON(" + buddy + ")");
            return;
        }
        logger.warning("AIM SIGN ON " + buddy);
        if (!aimbud.isOnline()) {
            aimbud.setOnline(true);
            for (int i = 0; i < aimListeners.size(); i++) {
                try {
                	logger.severe("SENDING HNDLE BUDDY SIGN ON");
                    ((AIMListener) aimListeners.get(i)).handleBuddySignOn(aimbud, message);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    /**
     * Send buddy sign off event to all listeners.
     * 
     * @param buddy
     *            that signed off
     * @param message
     */
    private void generateBuddySignOff(String buddy, String message) {
        AIMBuddy aimbud = getBuddy(buddy);
        if (aimbud == null) {
            logger.severe("ERROR:  NOTIFICATION ABOUT NON BUDDY SIGN OFF(" + buddy + ")");
            return;
        }

        // logger.info("XML = \n" + aimbud.toXML());
        aimbud.setOnline(false);
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleBuddySignOff(aimbud, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send buddy is available event to all listeners.
     * 
     * @param buddy
     *            The subject of the change.
     * @param message
     *            DOCUMENT ME!
     */
    private void generateBuddyAvailable(String buddy, String message) {
        AIMBuddy aimbud = getBuddy(buddy);
        if (aimbud == null) {
            logger.severe("ERROR:  NOTIFICATION ABOUT NON BUDDY AVAILABLE(" + buddy + ")");
            return;
        }
        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleBuddyAvailable(aimbud, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Send buddy is unavailable event to all listeners.
     * 
     * @param buddy
     *            The subject of the change.
     * @param message
     *            DOCUMENT ME!
     */
    private void generateBuddyUnavailable(String buddy, String message) {
        AIMBuddy aimbud = getBuddy(buddy);
        if (aimbud == null) {
            logger.severe("ERROR:  NOTIFICATION ABOUT NON BUDDY UNAVAILABLE(" + buddy + ")");
            return;
        }

        for (int i = 0; i < aimListeners.size(); i++) {
            try {
                ((AIMListener) aimListeners.get(i)).handleBuddyUnavailable(aimbud, message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * message recieved from aim
     * 
     * @param buffer
     */
    private void fromAIM(byte[] buffer) {
        try {
            String inString = new String(buffer);

            logger.info("*** AIM: " + inString + " ***");
            StringTokenizer inToken = new StringTokenizer(inString, ":");
            String command = inToken.nextToken();
            if (command.equals("IM_IN2")) {
                // treat every message received as verification
                this.connectionVerified = true;

                String from = imNormalize(inToken.nextToken());
                // whats this?
                inToken.nextToken();
                // whats this?
                inToken.nextToken();
                String mesg = inToken.nextToken();
                while (inToken.hasMoreTokens()) {
                    mesg = mesg + ":" + inToken.nextToken();
                }

                String request = stripHTML(mesg);

                if ((from.equalsIgnoreCase(this.name)) && (request.equals(AIMClient.PING))) {
                    logger.info("AIM CONNECTION VERIFIED(" + new Date() + ").");
                    return;
                }

                logger.info("*** AIM MESSAGE: " + from + " > " + request + " ***");

                // CALL ALL LISTENERS HERE
                generateMessage(from, request.trim());
                return;
            }

            if (command.equals("CONFIG2")) {
                if (inToken.hasMoreElements()) {
                    String config = inToken.nextToken();
                    while (inToken.hasMoreTokens()) {
                        config = config + ":" + inToken.nextToken();
                    }
                    processConfig(config);
                    logger.info("*** AIM CONFIG RECEIVED ***");
                } else {
                    setPermitMode(PERMIT_ALL);
                    logger.info("*** AIM NO CONFIG RECEIVED ***");
                }
                return;
            }

            if (command.equals("EVILED")) {
                int amount = Integer.parseInt(inToken.nextToken());
                String from = "anonymous";
                if (inToken.hasMoreElements()) {
                    from = imNormalize(inToken.nextToken());
                }

                // if what we have is less than what the server just sent, its
                // a warning
                // otherwise it was just a server decrement update
                if (getBuddy(name).getWarningAmount() < amount) {
                    generateWarning(from, amount);
                }

                return;
            }

            if (command.equals("UPDATE_BUDDY2")) {
                String bname = imNormalize(inToken.nextToken());
                AIMBuddy aimbud = getBuddy(bname);
                if (aimbud == null) {
                	aimbud = new AIMBuddy(bname);
                }
                String stat = inToken.nextToken();
                if (stat.equals("T")) {
                    generateBuddySignOn(bname, "INFO");
                     logger.info("Buddy:" + bname + " just signed on.");
                } else if (stat.equals("F")) {
                    generateBuddySignOff(bname, "INFO");
                    logger.info("Buddy:" + bname + " just signed off.");
                }
                int evilAmount = Integer.parseInt(inToken.nextToken());
                aimbud.setWarningAmount(evilAmount);
                if (stat.equals("T")) { // See whether user is available.
                    String signOnTime = inToken.nextToken();

                    // TODO: what is the format of this?
                    // System.err.println(bname+" signon="+signOnTime);
                    String idleTime = inToken.nextToken();
                    // System.err.println(bname+"
                    // idle="+Integer.valueOf(idleTime).intValue()+" mins");
                    if (-1 != inToken.nextToken().indexOf('U')) {
                        generateBuddyUnavailable(bname, "INFO");
                    } else {
                        generateBuddyAvailable(bname, "INFO");
                    }
                }

                return;
            }

            if (command.equals("ERROR")) {
                String error = inToken.nextToken();
                logger.severe("*** AIM ERROR: " + error + " ***");
                if (error.equals("901")) {
                    generateError(error, "Not currently available");
                    // logger.info("Not currently available");
                    return;
                }

                if (error.equals("902")) {
                    generateError(error, "Warning not currently available");
                    // logger.info("Warning not currently available");
                    return;
                }

                if (error.equals("903")) {
                    generateError(error, "Message dropped, sending too fast");
                    // logger.info("Message dropped, sending too fast");
                    return;
                }

                if (error.equals("960")) {
                    String person = inToken.nextToken();
                    generateError(error, "Sending messages too fast to " + person);
                    // logger.info("Sending messages too fast to " + person);
                    return;
                }

                if (error.equals("961")) {
                    String person = inToken.nextToken();
                    generateError(error, person + " sent you too big a message");
                    // logger.info(person + " sent you too big a message");
                    return;
                }

                if (error.equals("962")) {
                    String person = inToken.nextToken();
                    generateError(error, person + " sent you a message too fast");
                    // logger.info(person + " sent you a message too fast");
                    return;
                }

                if (error.equals("980")) {
                    generateError(error, "Invalid login information!");
                    logger.severe("Invalid login information");
                    return;
                }
                if (error.equals("983")) {
                    generateError(error, "Too many login attempts!");
                    logger.severe("Logging on too fast");
                    return;
                }

                if (error.equals("Signon err")) {
                    String text = inToken.nextToken();
                    generateError(error, "AIM Signon failure: " + text);

                    // logger.info("AIM Signon failure: " + text);
                    signoff("5");
                }

                return;
            }
        } catch (Exception e) {
            logger.severe("ERROR: failed to handle aim protocol properly");
            e.printStackTrace();
        }
    }


    /**
     * Processes AIM server-passed config string
     * 
     * @param config
     *            A properly formated TOC configuration.
     */
    private void processConfig(String config) {
        int new_permit_mode = PERMIT_ALL;
        BufferedReader br = new BufferedReader(new StringReader(config),512);
        try {
            String current_group = DEFAULT_GROUP;
            String line;
            while (null != (line = br.readLine())) {
                if (line.equals("done")) {
                    break;
                }
                char type = line.charAt(0);
                
                String arg = line.substring(2);
                switch (type) {
                    case 'g' :
                        current_group = arg;
                        break;
                    case 'b' :
                        // make a new buddy if they dont exist locally
                        AIMBuddy buddy = null;
                        int x = arg.indexOf(":");
                        String alias ="";
                        if(x != -1){ //this person has an alias
                        	alias = arg.substring(x+1);
                        	arg   = arg.substring(0,x);
                        }else{
                        	alias = arg;
                        }
                        buddy = (AIMBuddy) buddyHash.get(imNormalize(arg));
                        if (buddy == null) {
                            buddy = new AIMBuddy(arg, current_group,alias);
                            buddyHash.put(imNormalize(arg), buddy);
                        } else {
                            // they already exist, so just take the server's
                            // word
                            // for the group they belong in
                            buddy.setGroup(current_group);
                        }
                        break;
                    case 'p' :
                        permitted.add(imNormalize(arg));
                        break;
                    case 'd' :
                        denied.add(imNormalize(arg));
                        break;
                    case 'm' :
                        new_permit_mode = Integer.parseInt(arg);
                        break;
                }
            }
        } catch (IOException e) {
            logger.warning("Error reading configuration.");
            signoff("2.25");
            return;
        }
        logger.warning("Generating ConfigReady Handlevent");
        generateConfigReady(buddyHash);
        // this will "readd" existing buddies, but thats ok
        addBuddies(new ArrayList(buddyHash.values()));
        setPermitMode(new_permit_mode);
    }


    /**
     * internal method to send message to aim
     * 
     * @param to
     * @param text
     *            to send
     */
    void sendMesg(String to, String text) {
        if (text.length() >= 1024) {
            text = text.substring(0, 1024);
        }
        logger.info("Sending Message " + to + " > " + text);

        String work = "toc2_send_im ";
        work = work.concat(to);
        work = work.concat(" \"");
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '$' :
                case '{' :
                case '}' :
                case '[' :
                case ']' :
                case '(' :
                case ')' :
                case '\"' :
                case '\\' :
                    work = work.concat("\\" + text.charAt(i));
                    break;
                default :
                    work = work.concat("" + text.charAt(i));
                    break;
            }
        }

        work = work.concat("\"\0");
        // logger.info(work);
        try {
            frameSend(work);
        } catch (IOException e) {
            logger.severe("*** AIM ERROR: sending message.");
            e.printStackTrace();
            signoff("9");
        }
    }


    /**
     * Change availability. If the reason is the empty String, the user will be
     * made avaiable. Otherwise, it will be made away.
     * 
     * @param reason
     *            The reason explaining why the user is not avaiable.
     */
    private void sendAway(String reason) {
        final String work = "toc_set_away \"" + reason + "\"\0";

        try {
            frameSend(work);
        } catch (IOException e) {
            signoff("10");
        }
    }


    /**
     * sign off
     * 
     * @param place
     */
    private void signoff(String place) {
        online = false;
        logger.info("Trying to close IM (" + place + ").....");
        try {
            if (null != out) {
                out.close();
            }
            if (null != in) {
                in.close();
            }
            if (null != connection) {
                connection.close();
            }
        } catch (IOException e) {
            logger.severe(e.toString());
        }

        generateDisconnected();
        logger.info("*** AIM CLIENT SIGNED OFF.");
    }


    /**
     * Add a buddy to the denied list
     * 
     * @param buddy
     */
    public void denyBuddy(AIMBuddy buddy) {
        String bname = imNormalize(buddy.getName());
        permitted.remove(bname);
        denied.add(bname);
        sendDeny(bname);
    }


    /**
     * Add a buddy to the permitted list
     * 
     * @param buddy
     */
    public void permitBuddy(AIMBuddy buddy) {
        String bname = imNormalize(buddy.getName());
        denied.remove(bname);
        permitted.add(bname);
        sendPermit(bname);
    }


    /**
     * Gets the permit mode that is set on the server.
     * 
     * @return int representation (see public statics) of current permit mode.
     */
    public int getPermitMode() {
        return permitMode;
    }


    /**
     * Sets the permit mode on the server. (Use constants from AIMSender)
     * 
     * @param mode
     */
    public void setPermitMode(int mode) {
        if (mode < 1 || mode > 5) {
            logger.info("Invalid permit mode, ignoring:" + mode);
            return;
        } else if (mode == DENY_SOME && this.denied.size() == 0) {
            logger.info("Attempting to deny some, and none are denied, ignoring.");
            return;
        } else if (mode == PERMIT_SOME && this.permitted.size() == 0) {
            logger.info("Attempting to permit some, and none are permitted, ignoring.");
            return;
        }

        logger.info("Setting permit mode to:" + mode);
        permitMode = mode;
        try {
            frameSend("toc2_set_pdmode " + permitMode + "\0");
        } catch (IOException e) {
            e.printStackTrace();
            logger.severe("ERROR setting permit mode!");
        }
    }


    /**
     * Clear unvailable message
     */
    public void setAvailable() {
        sendAway("");
    }


    /**
     * Set unvailable message
     * 
     * @param reason
     */
    public void setUnavailable(String reason) {
        sendAway(reason);
    }


    /**
     * Try to verify our connections by messaging ourselves. Takes 2 instances
     * to check. One to send the message (sender=true), One to check the result
     * (sender=false)
     * 
     * @author Scott Oster
     * @created February 28, 2002
     */
    static class AimConnectionCheck extends TimerTask {
        AIMClient aim;
        private boolean sender;


        /**
         * Constructor for the AimConnectionCheck object
         * 
         * @param aim
         *            handle to aim
         * @param sender
         *            which instance it is
         */
        public AimConnectionCheck(AIMClient aim, boolean sender) {
            this.aim = aim;
            this.sender = sender;
        }


        /**
         * Main processing method for the AimConnectionCheck object
         */
        public void run() {
            try {
                if (sender) {
                    aim.connectionVerified = false;
                    // only message if we are online (when we get reconnected
                    // online will be true)
                    if (aim.online) {
                        aim.sendMesg(aim.name, AIMClient.PING);
                    }
                } else {
                    // need to see if we got a response
                    if (!aim.connectionVerified) {
                        // restart the connection if we didnt see the message
                        logger.info("*** AIM -- CONNECTION PROBLEM(" + new Date() + "): Connection was not verified!");
                        logger.info("****** Assuming it was dropped, issuing restart.");
                        aim.signoff("Connection Dropped!");
                        new Thread(aim).start();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}