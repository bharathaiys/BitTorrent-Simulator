import java.io.*;
import java.lang.reflect.Array;
import java.util.Vector;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;

public class peerProcess {
    protected String peerId;
    protected int numberOfPreferredNeighbours;
    protected int unchokingInterval;
    protected int optimisticUnchokingInterval;
    protected String fileName;
    protected int fileSize;
    protected int pieceSize;
    protected int lastPieceSize;
    protected int listeningPort;
    protected boolean hasFile;
    public ConcurrentLinkedQueue<Message> messageQueue;
    public ConcurrentHashMap<String, TCPConnectionInfo> peersToTCPConnectionsMapping;
    static String peerInfoConfig = "PeerInfo.cfg";
    static String commonConfig = "Common.cfg";
    public static Logger logger;
    private String peerHome;
    protected HashMap<String,RemotePeerInfo> peerInfoMap;
    protected Vector<RemotePeerInfo> peersToConnect;
    protected Vector<TCPConnectionInfo> activeConnections;
    protected ConcurrentHashMap<String, boolean[]> bitFieldsOfPeers;
    protected boolean[] myBitField;
    protected int numberOfPieces;
    protected AtomicReference<String> optimizedNeighbour;  // based on timer tasks
    protected ConcurrentLinkedQueue<String> interestedPeers;   // based on interested messages
    protected ConcurrentHashMap<String, Boolean> unchokeStatus; // based on timer tasks; irrespective of optimizedNeighbour
    protected ConcurrentLinkedQueue<String> preferredNeighbours;   // based on timer tasks
    protected ConcurrentHashMap<String, Integer> downloadRate;
    protected HashMap<String, Boolean> canRequestStatus;  // based on choking and unchoking messages myProcess receives
    protected HashSet<Integer> requestedPieces;
    protected HashSet<Integer> downloadedPieces;
    FileObject myFileObject;

    /**
     * Constructor for the peerProcess object. Initializes the peerId.
     * */
    peerProcess(String peerId) {
        this.peerId = peerId;
        peerHome = "peer_" + peerId + "/";
        initializeLogger();
        initializeConfig();
        getPeerInfo();
        activeConnections = new Vector<>();
        messageQueue = new ConcurrentLinkedQueue<>();
        peersToTCPConnectionsMapping = new ConcurrentHashMap<>();
        interestedPeers = new ConcurrentLinkedQueue<>();
        requestedPieces = new HashSet<>();
        downloadedPieces = new HashSet<>();
    }

    /**
     * Initializes Logger object for this peer
     */
    private void initializeLogger() {
        try {
            logger = new Logger(this.peerId);
            logger.start();
        } catch (Exception ex) {
        }
    }

    /**
     * Initializes the Bit Field of the node and the File object based on the hasFile value.
     * */
    private void initializeBitFiledAndFile(){
        try {
            this.myBitField = new boolean[numberOfPieces];
            File peerDir = new File(peerHome);
            boolean mkdirRes = peerDir.mkdir();
            String filePath = peerHome + fileName;
            if (this.hasFile) {
                Arrays.fill(myBitField, true);

            } else {
                Arrays.fill(myBitField, false);
                //Create an empty file to write pieces to.
                File newFile = new File(filePath);
                boolean createNewFileRes = newFile.createNewFile();
            }
            myFileObject = new FileObject(filePath, fileSize, pieceSize);
        }catch(IOException ex){

        }
    }

    /**
     * Initialize the config parameters in the local datastructures. Read from the common.cfg file.
     * If the node does not have the target file, create a new file to read/write the shared pieces.
     * Initialize the bitfields of this node and all the peers in the P2P network.
     */
    void initializeConfig() {
        String st;
        try {
            BufferedReader in = new BufferedReader(new FileReader(commonConfig));
            System.out.println("\nINITIALIZING COMMON.CFG\n");
            while ((st = in.readLine()) != null) {                
                String[] tokens = st.split("\\s+");
                switch (tokens[0]) {
                    case "NumberOfPreferredNeighbors":  // 2
                        System.out.println("NumberOfPreferredNeighbors: "+tokens[1]);
                        this.numberOfPreferredNeighbours = Integer.parseInt(tokens[1]);
                        break;
                    case "UnchokingInterval":   // 5
                        System.out.println("UnchokingInterval: "+tokens[1]);
                        this.unchokingInterval = Integer.parseInt(tokens[1]);
                        break;
                    case "OptimisticUnchokingInterval": // 15
                        System.out.println("OptimisticUnchokingInterval: "+tokens[1]);
                        this.optimisticUnchokingInterval = Integer.parseInt(tokens[1]);
                        break;
                    case "FileName":    // The File.dat
                        System.out.println("FileName: "+tokens[1]);
                        this.fileName = tokens[1];
                        break;
                    case "FileSize":    // 10
                        System.out.println("FileSize: "+tokens[1]);
                        this.fileSize = Integer.parseInt(tokens[1]);
                        break;
                    case "PieceSize":   // 1
                        System.out.println("PieceSize: "+tokens[1]+"\n");
                        this.pieceSize = Integer.parseInt(tokens[1]);
                        break;
                    default:
                        throw new Exception("Invalid parameter in the file Common.cfg");
                }
            }
            numberOfPieces = fileSize / pieceSize;
            if (fileSize % pieceSize != 0) {
                numberOfPieces++;
                lastPieceSize = fileSize % pieceSize;
            }
            in.close();
        } catch (Exception ex) {

        }
    }

    /**
     * Get the information about all the peers in the network.
     * Select the peers for which the TCP connection needs to be initiated by this node.
     * Only the previously seen nodes in the peers list are requested for TCP connections.
     * All the nodes succeeding the current node in the PeerInfo.cfg file need to send connection request to this node.
     * */
    void getPeerInfo() {
        String st;
        bitFieldsOfPeers = new ConcurrentHashMap<>();
        peerInfoMap = new HashMap<>();
        peersToConnect = new Vector<>();
        canRequestStatus = new HashMap<>();
        downloadRate = new ConcurrentHashMap<>();
        optimizedNeighbour = new AtomicReference<>();
        preferredNeighbours = new ConcurrentLinkedQueue<>();
        unchokeStatus = new ConcurrentHashMap<>();
        boolean makeConnections = true;
        try {
            System.out.println("INITIALIZING PEERINFO.CFG\n");
            BufferedReader in = new BufferedReader(new FileReader(peerInfoConfig));
            while ((st = in.readLine()) != null) {
                System.out.println(st);
                String[] tokens = st.split("\\s+");
                RemotePeerInfo newNode = new RemotePeerInfo(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3]));
                if (newNode.peerId.equals(this.peerId)) {
                    this.listeningPort = Integer.parseInt(newNode.peerPort);
                    this.hasFile = newNode.hasFile;
                    initializeBitFiledAndFile();
                    makeConnections = false;
                } else {
                    peerInfoMap.put(newNode.peerId, newNode);
                    canRequestStatus.put(newNode.peerId, false);
                    downloadRate.put(newNode.peerId, 0);
                    unchokeStatus.put(newNode.peerId, true);
                    bitFieldsOfPeers.put(newNode.peerId, new boolean[numberOfPieces]);
                }
                if (makeConnections) {
                    peersToConnect.addElement(newNode);
                }
            }
            System.out.println();
            in.close();
        } catch (Exception ex) {
        }
    }

    /**
     * This is the starting point for a peer node. TCP connections are established here with all the nodes in the P2P network.
     * It then creates a Message handler to handle all the messages received by the node.
     * */
    public static void main(String[] args) {
        peerProcess peerNode = new peerProcess(args[0]);
        MessageHandler myMessageHandler = new MessageHandler(peerNode);
//       new Thread(myMessageHandler).start();
        Thread messageHandlerThread = new Thread(myMessageHandler);
        ArrayList<Thread> listenerThreads = new ArrayList<>();
        messageHandlerThread.start();
        NeighbourHandler myNeighbourHandler = new NeighbourHandler(myMessageHandler);
        myNeighbourHandler.runUnchokeTasks();
        try {
            int remainingPeers = peerNode.peerInfoMap.size();
            // Send connection requests to all the peers listed in peersToConnect
            for (RemotePeerInfo node : peerNode.peersToConnect) {
                Socket requestSocket = new Socket(node.peerAddress, Integer.parseInt(node.peerPort));
                ObjectInputStream in = new ObjectInputStream(requestSocket.getInputStream());
                ObjectOutputStream out = new ObjectOutputStream(requestSocket.getOutputStream());
                TCPConnectionInfo newTCPConnection = new TCPConnectionInfo(requestSocket, in, out, peerNode.peerId);
                //Create a listener thread.
                Runnable newListenerThread = new ListenerThread(newTCPConnection, peerNode.messageQueue, peerNode.peersToTCPConnectionsMapping);
//                logger.writeLog(LogMessage.CLIENT_CONNECT,peerNode.);
                Thread newListener = new Thread(newListenerThread);
                listenerThreads.add(newListener);
                newListener.start();
                remainingPeers--;
                peerNode.activeConnections.addElement(newTCPConnection);
            }
            //Start a server on this node and wait for the remaining nodes to send connection requests
            ServerSocket listener = new ServerSocket(peerNode.listeningPort);
            while (remainingPeers > 0) {
                Socket listenSocket = listener.accept();
                ObjectOutputStream out = new ObjectOutputStream(listenSocket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(listenSocket.getInputStream());
                TCPConnectionInfo newTCPConnection = new TCPConnectionInfo(listenSocket, in, out, peerNode.peerId);
                Runnable newThread = new ListenerThread(newTCPConnection, peerNode.messageQueue, peerNode.peersToTCPConnectionsMapping);
                new Thread(newThread).start();
                peerNode.activeConnections.addElement(newTCPConnection);
                remainingPeers--;
            }
            listener.close();
        } catch (Exception ex) {
        }
        boolean isFileSharedToAllPeers = false;
        boolean addedLogFor_downloadedAllFiles = false;
        while (!isFileSharedToAllPeers) {
            //Wait until the peer sharing process is finished
            isFileSharedToAllPeers = true;
            for(boolean hasPiece: peerNode.myBitField) {
                if (!hasPiece) {
                    isFileSharedToAllPeers = false;
                    break;
                }
            }
            for (boolean[] bitField : peerNode.bitFieldsOfPeers.values()) {
                for (boolean hasPiece : bitField) {
                    if (!hasPiece) {
                        isFileSharedToAllPeers = false;
                        break;
                    }
                }
            }
            if(peerNode.downloadedPieces.size()==peerNode.numberOfPieces && !addedLogFor_downloadedAllFiles){
                addedLogFor_downloadedAllFiles = true;
                peerProcess.logger.writeLog(LogMessage.DOWNLOAD_COMPLETE,null);
            }
        }
        messageHandlerThread.interrupt();
        for(Thread t: listenerThreads){
            t.interrupt();
        }
        peerNode.myFileObject.cleanUp();
        try {
            logger.stop();
        }catch (Exception ex){
        }
        System.out.println("Terminating Program");
        System.exit(0);
    }
}
