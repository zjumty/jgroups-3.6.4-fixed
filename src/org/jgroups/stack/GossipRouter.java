package org.jgroups.stack;

import org.jgroups.Address;
import org.jgroups.PhysicalAddress;
import org.jgroups.protocols.PingData;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.annotations.Property;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.util.*;
import org.jgroups.util.UUID;

import javax.management.MBeanServer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Router for TCP based group comunication (using layer TCP instead of UDP). Instead of the TCP
 * layer sending packets point-to-point to each other member, it sends the packet to the router
 * which - depending on the target address - multicasts or unicasts it to the group / or single member.
 * <p/>
 * This class is especially interesting for applets which cannot directly make connections (neither
 * UDP nor TCP) to a host different from the one they were loaded from. Therefore, an applet would
 * create a normal channel plus protocol stack, but the bottom layer would have to be the TCP layer
 * which sends all packets point-to-point (over a TCP connection) to the router, which in turn
 * forwards them to their end location(s) (also over TCP). A centralized router would therefore have
 * to be running on the host the applet was loaded from.
 * <p/>
 * An alternative for running JGroups in an applet (IP multicast is not allows in applets as of
 * 1.2), is to use point-to-point UDP communication via the gossip server. However, then the appplet
 * has to be signed which involves additional administrative effort on the part of the user.
 * <p/>
 * Note that a GossipRouter is also a good way of running JGroups in Amazon's EC2 environment which (as of summer 09)
 * doesn't support IP multicasting.
 * @author Bela Ban
 * @author Vladimir Blagojevic
 * @author Ovidiu Feodorov <ovidiuf@users.sourceforge.net>
 * @since 2.1.1
 */
public class GossipRouter {
    public static final byte CONNECT=1;    // CONNECT(group, addr) --> local address
    public static final byte DISCONNECT=2; // DISCONNECT(group, addr)
    public static final byte GOSSIP_GET=4; // GET(group) --> List<addr> (members)
    public static final byte MESSAGE=10;
    public static final byte SUSPECT=11;
    public static final byte PING=12;
    public static final byte CLOSE=13;
    public static final byte CONNECT_OK=14;
    public static final byte OP_FAIL=15;  
    public static final byte DISCONNECT_OK=16;
    
    

    public static final int PORT=12001;

    @ManagedAttribute(description="server port on which the GossipRouter accepts client connections", writable=true)
    private int port;

    @ManagedAttribute(description="address to which the GossipRouter should bind", writable=true, name="bind_address")
    private String bindAddressString;
    
    @ManagedAttribute(description="time (in msecs) until gossip entry expires", writable=true)
    private long expiryTime=0;

    // Maintains associations between groups and their members
    private final ConcurrentMap<String, ConcurrentMap<Address, ConnectionHandler>> routingTable=new ConcurrentHashMap<>();

    /**
     * Store physical address associated with a logical address. Used mainly by TCPGOSSIP
     */
    private final Map<Address, PhysicalAddress> address_mappings=new ConcurrentHashMap<>();

    private ServerSocket srvSock=null;
    private InetAddress bindAddress=null;

    @Property(description="Time (in ms) for setting SO_LINGER on sockets returned from accept(). 0 means do not set SO_LINGER")
    private long linger_timeout=2000L;

    @Property(description="Time (in ms) for SO_TIMEOUT on sockets returned from accept(). 0 means don't set SO_TIMEOUT")
    private long sock_read_timeout=0L;

    @Property(description="The max queue size of backlogged connections")
    private int backlog=1000;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @ManagedAttribute(description="whether to discard message sent to self", writable=true)
    private boolean discard_loopbacks=false;

    protected List<ConnectionTearListener> connectionTearListeners=new CopyOnWriteArrayList<>();

    protected ThreadFactory default_thread_factory=new DefaultThreadFactory("gossip-handlers", true, true);

    protected Timer timer=null;

    protected final Log log=LogFactory.getLog(this.getClass());

    private boolean jmx=false;

    private boolean registered=false;

    public GossipRouter() {
        this(PORT);
    }

    public GossipRouter(int port) {
        this(port, null);
    }

    public GossipRouter(int port, String bindAddressString) {
        this(port,bindAddressString,false,0);
    }

    public GossipRouter(int port, String bindAddressString, boolean jmx) {
        this(port, bindAddressString,jmx,0);    
    }
    
    public GossipRouter(int port, String bindAddressString, boolean jmx, long expiryTime) {
        this.port = port;
        this.bindAddressString = bindAddressString;
        this.jmx = jmx;
        this.expiryTime = expiryTime;
        this.connectionTearListeners.add(new FailureDetectionListener());
    }

    public void setPort(int port) {
        this.port=port;
    }

    public int getPort() {
        return port;
    }

    public void setBindAddress(String bindAddress) {
        bindAddressString=bindAddress;
    }

    public String getBindAddress() {
        return bindAddressString;
    }

    public int getBacklog() {
        return backlog;
    }

    public void setBacklog(int backlog) {
        this.backlog=backlog;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public long getExpiryTime() {
        return expiryTime;
    }


    @ManagedAttribute(description="status")
    public boolean isStarted() {
        return isRunning();
    }

    public boolean isDiscardLoopbacks() {
        return discard_loopbacks;
    }

    public void setDiscardLoopbacks(boolean discard_loopbacks) {
        this.discard_loopbacks=discard_loopbacks;
    }

    public long getLingerTimeout() {
        return linger_timeout;
    }

    public void setLingerTimeout(long linger_timeout) {
        this.linger_timeout=linger_timeout;
    }

    public long getSocketReadTimeout() {
        return sock_read_timeout;
    }

    public void setSocketReadTimeout(long sock_read_timeout) {
        this.sock_read_timeout=sock_read_timeout;
    }

    public ThreadFactory getDefaultThreadPoolThreadFactory() {
        return default_thread_factory;
    }

    public static String type2String(int type) {
        switch (type) {
            case CONNECT:       return "CONNECT";
            case DISCONNECT:    return "DISCONNECT";
            case GOSSIP_GET:    return "GOSSIP_GET";
            case MESSAGE:       return "MESSAGE";
            case SUSPECT:       return "SUSPECT";
            case PING:          return "PING";
            case CLOSE:         return "CLOSE";
            case CONNECT_OK:    return "CONNECT_OK";
            case DISCONNECT_OK: return "DISCONNECT_OK";
            case OP_FAIL:       return "OP_FAIL";
            default:            return "unknown (" + type + ")";
        }
    }


    /**
     * Lifecycle operation. Called after create(). When this method is called, the managed attributes
     * have already been set.<br>
     * Brings the Router into a fully functional state.
     */
    @ManagedOperation(description="Lifecycle operation. Called after create(). When this method is called, "
            + "the managed attributes have already been set. Brings the Router into a fully functional state.")
    public void start() throws Exception {
        if(running.compareAndSet(false, true)) {           
            if(jmx && !registered) {
                MBeanServer server=Util.getMBeanServer();
                JmxConfigurator.register(this, server, "jgroups:name=GossipRouter");
                registered=true;
            }
    
            if(bindAddressString != null) {
                bindAddress=InetAddress.getByName(bindAddressString);
                srvSock=new ServerSocket(port, backlog, bindAddress);
            }
            else {
                srvSock=new ServerSocket(port, backlog);
            }       
    
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    GossipRouter.this.stop();
                }
            });
    
            // start the main server thread
            new Thread(new Runnable() {
                public void run() {
                    mainLoop();
                }
            }, "GossipRouter").start();
            
            long tmp_expiry = getExpiryTime();
            if (tmp_expiry > 0) {
                timer = new Timer(true);
                timer.schedule(new TimerTask() {
                    public void run() {
                        sweep();
                    }
                }, tmp_expiry, tmp_expiry);
            }            
        } else {
            throw new Exception("Router already started.");
        }
    }

    /**
     * Always called before destroy(). Close connections and frees resources.
     */
    @ManagedOperation(description="Always called before destroy(). Closes connections and frees resources")
    public void stop() {
        clear();
        if(running.compareAndSet(true, false)){
            Util.close(srvSock);            
            log.debug("router stopped");
        }
    }

    @ManagedOperation(description="Closes all connections and clears routing table (leave the server socket open)")
    public void clear() {
        if(running.get()) {
            for(ConcurrentMap<Address,ConnectionHandler> map: routingTable.values()) {
                for(ConnectionHandler ce: map.values())
                    ce.close();
            }
            routingTable.clear();
        }
    }

    public void destroy() {
    }
    
    @ManagedAttribute(description="operational status", name="running")
    public boolean isRunning() {
        return running.get();
    }

    @ManagedOperation(description="dumps the contents of the routing table")
    public String dumpRoutingTable() {
        String label="routing";
        StringBuilder sb=new StringBuilder();

        if(routingTable.isEmpty()) {
            sb.append("empty ").append(label).append(" table");
        }
        else {
            boolean first=true;
            for(Map.Entry<String, ConcurrentMap<Address, ConnectionHandler>> entry : routingTable.entrySet()) {
                String gname=entry.getKey();
                if(!first)
                    sb.append("\n");
                else
                    first=false;
                sb.append(gname + ": ");
                Map<Address,ConnectionHandler> map=entry.getValue();
                if(map == null || map.isEmpty()) {
                    sb.append("null");
                }
                else {
                    sb.append(Util.printListWithDelimiter(map.keySet(), ", "));
                }
            }
        }
        return sb.toString();
    }

    @ManagedOperation(description="dumps the contents of the routing table")
    public String dumpRoutingTableDetailed() {
        String label="routing";
        StringBuilder sb=new StringBuilder();

        if(routingTable.isEmpty()) {
            sb.append("empty ").append(label).append(" table");
        }
        else {
            boolean first=true;
            for(Map.Entry<String, ConcurrentMap<Address, ConnectionHandler>> entry : routingTable.entrySet()) {
                String gname=entry.getKey();
                if(!first)
                    sb.append("\n");
                else
                    first=false;
                sb.append(gname + ":\n");
                Map<Address,ConnectionHandler> map=entry.getValue();
                if(map == null || map.isEmpty()) {
                    sb.append("null");
                }
                else {
                    for(Map.Entry<Address,ConnectionHandler> en: map.entrySet()) {
                        sb.append(en.getKey() + ": ");
                        ConnectionHandler handler=en.getValue();
                        sb.append("sock=" +handler.sock).append("\n");
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @ManagedOperation(description="dumps the mappings between logical and physical addresses")
    public String dumpAddresssMappings() {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<Address,PhysicalAddress> entry: address_mappings.entrySet())
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        return sb.toString();
    }

    private void mainLoop() {
        if(bindAddress == null)
            bindAddress=srvSock.getInetAddress();

        printStartupInfo();

        while(isRunning()) {
            Socket sock=null;
            try {
                sock=srvSock.accept();
                if(linger_timeout > 0) {
                    int linger=Math.max(1, (int)(linger_timeout / 1000));
                    sock.setSoLinger(true, linger);
                }
                if(sock_read_timeout > 0)
                    sock.setSoTimeout((int)sock_read_timeout);

                log.debug("Accepted connection, socket is %s", sock);
                
                ConnectionHandler ch=new ConnectionHandler(sock);
                getDefaultThreadPoolThreadFactory().newThread(ch).start();
            }
            catch(IOException e) {
                //only consider this exception if GR is not shutdown
                if(isRunning()) {
                    log.error("failure handling connection from %s: %s", sock, e);
                    Util.close(sock);
                }
            }
        }
    }
    
    /**
     * Removes expired gossip entries (entries older than EXPIRY_TIME msec).
     * @since 2.2.1
     */
    private void sweep() {
        long diff, currentTime = System.currentTimeMillis();       
        List <ConnectionHandler> victims = new ArrayList<>();
        for (Iterator<Entry<String, ConcurrentMap<Address, ConnectionHandler>>> it = routingTable.entrySet().iterator(); it.hasNext();) {
            Map<Address, ConnectionHandler> map = it.next().getValue();            
            if (map == null || map.isEmpty()) {
                it.remove();
                continue;
            }
            for (Iterator<Entry<Address, ConnectionHandler>> it2 = map.entrySet().iterator(); it2.hasNext();) {
                ConnectionHandler ch = it2.next().getValue();                
                diff = currentTime - ch.timestamp;
                if (diff > expiryTime) {                    
                    victims.add(ch);                                                           
                }
            }
        }

        for (ConnectionHandler v : victims) {
            v.close();
        }        
    }
    
    private void route(Address dest, String group, byte[] msg) {
        if(dest == null) { // send to all members in group
            if(group == null)
                log.error("group is null");
            else
                sendToAllMembersInGroup(group, msg);
        }
        else { // send unicast

            ConnectionHandler handler=findAddressEntry(group, dest);
            if(handler == null) {
                log.trace("cannot find %s in the routing table, \nrouting table=%s\n", dest, dumpRoutingTable());
                return;
            }
            if(handler.output == null) {
                log.error("%s is associated with a null output stream", dest);
                return;
            }
            try {
                sendToMember(dest, handler.output, msg);
            }
            catch(Exception e) {
                log.error("failed sending message to %s: %s", dest, e.getMessage());
                removeEntry(group, dest); // will close socket
            }
        }
    }



    private void removeEntry(String group, Address addr) {
        // Remove from routing table
        ConcurrentMap<Address, ConnectionHandler> map;
        if(group != null) {
            map=routingTable.get(group);
            if(map != null && map.remove(addr) != null) {
                log.trace("Removed %s from group %s", addr, group);
                
                if(map.isEmpty()) {
                    boolean removed=removeGroupIfEmpty(group);
                    if(removed)
                        log.trace("Removed group %s", group);
                }                
            }
        }
        else {
            for(Map.Entry<String,ConcurrentMap<Address,ConnectionHandler>> entry: routingTable.entrySet()) {
                map=entry.getValue();
                if(map != null && map.remove(addr) != null && map.isEmpty()) {
                    boolean removed=removeGroupIfEmpty(entry.getKey());
                    if(removed)
                        log.trace("Removed %s from group %s", entry.getKey(), group);
                }
            }
        }

        address_mappings.remove(addr);
        UUID.remove(addr);
    }


    protected boolean removeGroupIfEmpty(String group) {
        if(group == null)
            return false;
        synchronized(routingTable) {
            ConcurrentMap<Address,ConnectionHandler> val=routingTable.get(group);
            if(val != null && val.isEmpty()) {
                routingTable.remove(group);
                return true;
            }
            return false;
        }
    }


    /**
     * @return null if not found
     */
    private ConnectionHandler findAddressEntry(String group, Address addr) {
        if(group == null || addr == null)
            return null;
        ConcurrentMap<Address,ConnectionHandler> map=routingTable.get(group);
        if(map == null)
            return null;
        return map.get(addr);
    }

    private void sendToAllMembersInGroup(String group, byte[] msg) {
        final ConcurrentMap<Address,ConnectionHandler> map=routingTable.get(group);
        if(map == null || map.isEmpty()) {
            log.warn("didn't find any members for group %s", group);
            return;
        }

        synchronized(map) {
            for(Map.Entry<Address,ConnectionHandler> entry: map.entrySet()) {
                ConnectionHandler handler=entry.getValue();
                DataOutputStream dos=handler.output;

                if(dos != null) {
                    try {
                        sendToMember(null, dos, msg);
                    }
                    catch(Exception e) {
                        log.warn("cannot send to %s: %s", entry.getKey(), e.getMessage());
                    }
                }
            }
        }
    }

    private static void sendToMember(Address dest, final DataOutputStream out, byte[] msg) throws Exception {
        if(out == null)
            return;
        synchronized(out) {
            GossipData request=new GossipData(GossipRouter.MESSAGE, null, dest, msg);
            request.writeTo(out);
            out.flush();
        }
    }

    private void notifyAbnormalConnectionTear(final ConnectionHandler ch, final Exception e) {
        for (ConnectionTearListener l : connectionTearListeners) {
            l.connectionTorn(ch, e);
        }
    }

    public interface ConnectionTearListener {
        public void connectionTorn(ConnectionHandler ch, Exception e);
    }

    /*
    * https://jira.jboss.org/jira/browse/JGRP-902
    */
    class FailureDetectionListener implements ConnectionTearListener {

        public void connectionTorn(ConnectionHandler ch, Exception e) {
            Set<String> groups = ch.known_groups;
            for (String group : groups) {
                if(group == null)
                    continue;
                Map<Address, ConnectionHandler> map = routingTable.get(group);
                if (map != null && !map.isEmpty()) {
                    for (Iterator<Entry<Address, ConnectionHandler>> i = map.entrySet().iterator(); i.hasNext();) {
                        ConnectionHandler entry = i.next().getValue();
                        DataOutputStream stream = entry.output;
                        try {
                            for (Address a : ch.logical_addrs) {
                                GossipData suspect = new GossipData(GossipRouter.SUSPECT);
                                suspect.writeTo(stream);
                                Util.writeAddress(a, stream);
                                stream.flush();
                            }
                        } catch (Exception ioe) {
                            // intentionally ignored
                        }
                    }
                }
            }
        }
    }

    /**
     * Prints startup information.
     */
    private void printStartupInfo() {
        System.out.println("GossipRouter started at " + new Date());

        System.out.print("Listening on port " + port);
        System.out.println(" bound on address " + bindAddress);

        System.out.print("Backlog is " + backlog);
        System.out.print(", linger timeout is " + linger_timeout);
        System.out.println(", and read timeout is " + sock_read_timeout);
    }


    /**
     * Handles the requests from a client (RouterStub)
     */
    class ConnectionHandler implements Runnable {
        private final AtomicBoolean active = new AtomicBoolean(false);
        private final Socket sock;
        private final DataOutputStream output;
        private final DataInputStream input;
        private final List<Address> logical_addrs=new ArrayList<>();
        Set<String> known_groups = new HashSet<>();
        private long timestamp;

        public ConnectionHandler(Socket sock) throws IOException {
            this.sock=sock;
            this.input=new DataInputStream(sock.getInputStream());
            this.output=new DataOutputStream(sock.getOutputStream());
        }

        void close() {
            if(active.compareAndSet(true, false)) {
                Util.close(input, output);
                Util.close(sock);
                for(Address addr: logical_addrs)
                    removeEntry(null, addr);
            }
        }

        public void run() {
            if(active.compareAndSet(false, true)) {
                try {
                    readLoop();
                }
                finally {
                    close();
                }
            }
        }
        
        public boolean isRunning() {
            return active.get();
        }

        private void readLoop() {
            while(isRunning()) {
                GossipData request;
                Address addr;
                String group;
                try {                   
                    request=new GossipData();
                    request.readFrom(input);
                    byte command=request.getType();
                    addr=request.getAddress();
                    group=request.getGroup();
                    known_groups.add(group);

                    timestamp = System.currentTimeMillis();
                    if(log.isTraceEnabled())
                        log.trace("received %s", request);
                    
                    switch(command) {

                        case GossipRouter.CONNECT:
                            handleConnect(request, addr, group);
                            break;

                        case GossipRouter.PING:
                            // do nothing here - client doesn't expect response data
                            break;

                        case GossipRouter.MESSAGE:
                            if(request.buffer == null || request.buffer.length == 0) {
                                log.warn("received null message");
                                break;
                            }

                            try {
                                route(addr, request.getGroup(), request.getBuffer());
                            }
                            catch(Exception e) {
                                log.error("failed in routing request to %s: %s", addr, e);
                            }
                            break;

                        case GossipRouter.GOSSIP_GET:
                            List<PingData> mbrs=new ArrayList<>();
                            ConcurrentMap<Address,ConnectionHandler> map=routingTable.get(group);
                            if(map != null) {
                                for(Address logical_addr: map.keySet()) {
                                    PhysicalAddress physical_addr=address_mappings.get(logical_addr);
                                    PingData rsp=new PingData(logical_addr, true, UUID.get(logical_addr), physical_addr);
                                    mbrs.add(rsp);
                                }
                            }
                            output.writeShort(mbrs.size());
                            for(PingData data: mbrs)
                                data.writeTo(output);
                            output.flush();
                            log.debug("responded to GOSSIP_GET with %s", mbrs);
                            break;

                        case GossipRouter.DISCONNECT:
                            try {
                                removeEntry(group, addr);
                                sendData(new GossipData(DISCONNECT_OK));
                            }
                            catch(Exception e) {
                                sendData(new GossipData(OP_FAIL));
                            }
                            break;
                            
                        case GossipRouter.CLOSE:
                            close();
                            break;
                            
                        case -1: // EOF
                            notifyAbnormalConnectionTear(this, new EOFException("Connection broken"));
                            break;
                    }
                    if(log.isTraceEnabled())
                        log.trace("processed %s", request);
                }
                catch(SocketTimeoutException ste) {
                }                
                catch(IOException ioex) {
                    notifyAbnormalConnectionTear(this, ioex);
                    break;
                }                
                catch(Exception ex) {
                    if (active.get()) {
                        log.warn("Exception in ConnectionHandler thread", ex);
                    }
                    break;
                }
            }
        }

        private void handleConnect(GossipData request, Address addr, String group) throws Exception {
            try {
                checkExistingConnection(addr, group);
                
                String logical_name = request.getLogicalName();
                if (logical_name != null && addr instanceof org.jgroups.util.UUID)
                    org.jgroups.util.UUID.add(addr, logical_name);

                // group name, logical address, logical name, physical addresses (could be null)
                logical_addrs.add(addr); // allows us to remove the entries for this connection on socket close

                addGroup(group, addr, this);

                if(request.getPhysicalAddress() != null)
                    address_mappings.put(addr, request.getPhysicalAddress());
                sendStatus(CONNECT_OK);
                log.debug("connection handshake completed, added %s to group %s", addr, group);
            } catch (Exception e) {
                removeEntry(group, addr);
                sendStatus(OP_FAIL);
                throw new Exception("Unsuccessful connection setup handshake for " + this);
            }
        }

        protected void addGroup(String group, Address addr, ConnectionHandler handler) {
            if(group == null || handler == null)
                return;
            synchronized(routingTable) {
                ConcurrentMap<Address,ConnectionHandler> map=routingTable.get(group);
                if(map == null) {
                    map=new ConcurrentHashMap<>();
                    routingTable.put(group, map);
                }
                map.put(addr, this);
            }
        }
        
        private boolean checkExistingConnection(Address addr, String group) throws Exception {
            boolean isOldExists = false;
            if (address_mappings.containsKey(addr)) {
                ConcurrentMap<Address, ConnectionHandler> map = null;
                ConnectionHandler oldConnectionH = null;
                if (group != null) {
                    map = routingTable.get(group);
                    if (map != null) {
                        oldConnectionH = map.get(addr);
                    }
                } else {
                    for (Map.Entry<String, ConcurrentMap<Address, ConnectionHandler>> entry : routingTable
                                    .entrySet()) {
                        map = entry.getValue();
                        if (map != null) {
                            oldConnectionH = map.get(addr);
                        }
                    }
                }
                if (oldConnectionH != null) {
                    isOldExists = true;
                    log.debug("Found old connection[%s] for %s. Closing old connection", oldConnectionH, addr);
                    oldConnectionH.close();
                }
                else
                    log.debug("No old connection for %s exists", addr);
            }
            return isOldExists;
        }
             
        private void sendStatus(byte status) {
            try {                
                output.writeByte(status);
                output.flush();                
            } catch (IOException e1) {
                //ignored
            }
        }
        
        private void sendData(GossipData data) {
            try {                
                data.writeTo(output);
                output.flush();                
            } catch (Exception e1) {
                //ignored
            }
        }

        public String toString() {
            StringBuilder sb=new StringBuilder();
            sb.append("ConnectionHandler[peer: " + sock.getInetAddress());
            if(!logical_addrs.isEmpty())
                sb.append(", logical_addrs: " + Util.printListWithDelimiter(logical_addrs, ", "));
            sb.append("]");
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        int port=12001;
        int backlog=0;
        long soLinger=-1;
        long soTimeout=-1;
        long expiry_time=60000;

        GossipRouter router=null;
        String bind_addr=null;
        boolean jmx=true;

        for(int i=0; i < args.length; i++) {
            String arg=args[i];
            if("-port".equals(arg)) {
                port=Integer.parseInt(args[++i]);
                continue;
            }
            if("-bindaddress".equals(arg) || "-bind_addr".equals(arg)) {
                bind_addr=args[++i];
                continue;
            }
            if("-backlog".equals(arg)) {
                backlog=Integer.parseInt(args[++i]);
                continue;
            }
            if("-expiry".equals(arg)) {
                expiry_time=Long.parseLong(args[++i]);
                continue;
            }
            if("-jmx".equals(arg)) {
                jmx=Boolean.valueOf(args[++i]);
                continue;
            }
            // this option is not used and should be deprecated/removed in a future release
            if("-timeout".equals(arg)) {
                System.out.println("    -timeout is deprecated and will be ignored");
                ++i;
                continue;
            }
            // this option is not used and should be deprecated/removed in a future release
            if("-rtimeout".equals(arg)) {
                System.out.println("    -rtimeout is deprecated and will be ignored");
                ++i;
                continue;
            }
            if("-solinger".equals(arg)) {
                soLinger=Long.parseLong(args[++i]);
                continue;
            }
            if("-sotimeout".equals(arg)) {
                soTimeout=Long.parseLong(args[++i]);
                continue;
            }
            help();
            return;
        }
        System.out.println("GossipRouter is starting. CTRL-C to exit JVM");

        try {
            router=new GossipRouter(port, bind_addr, jmx);

            if(backlog > 0)
                router.setBacklog(backlog);

            if(soTimeout >= 0)
                router.setSocketReadTimeout(soTimeout);

            if(soLinger >= 0)
                router.setLingerTimeout(soLinger);

            if(expiry_time > 0)
                router.setExpiryTime(expiry_time);

            router.start();
        }
        catch(Exception e) {
            System.err.println(e);
        }
    }

    static void help() {
        System.out.println();
        System.out.println("GossipRouter [-port <port>] [-bind_addr <address>] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println();

        System.out.println("    -backlog <backlog>    - Max queue size of backlogged connections. Must be");
        System.out.println("                            greater than zero or the default of 1000 will be");
        System.out.println("                            used.");
        System.out.println();
        System.out.println("    -jmx <true|false>     - Expose attributes and operations via JMX.");
        System.out.println();
        System.out.println("    -solinger <msecs>     - Time for setting SO_LINGER on connections. 0");
        System.out.println("                            means do not set SO_LINGER. Must be greater than");
        System.out.println("                            or equal to zero or the default of 2000 will be");
        System.out.println("                            used.");
        System.out.println();
        System.out.println("    -sotimeout <msecs>    - Time for setting SO_TIMEOUT on connections. 0");
        System.out.println("                            means don't set SO_TIMEOUT. Must be greater than");
        System.out.println("                            or equal to zero or the default of 3000 will be");
        System.out.println("                            used.");
        System.out.println();
        System.out.println("    -expiry <msecs>       - Time for closing idle connections. 0");
        System.out.println("                            means don't expire.");
        System.out.println();
    }
}