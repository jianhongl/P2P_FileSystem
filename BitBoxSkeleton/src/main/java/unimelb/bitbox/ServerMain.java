package unimelb.bitbox;

import java.io.*;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;


import unimelb.bitbox.util.*;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;

import javax.print.Doc;

public class ServerMain extends Thread implements FileSystemObserver {
	private static Logger log = Logger.getLogger(ServerMain.class.getName());
	protected static FileSystemManager fileSystemManager;
	public  static String mode;
	private static Socket client;
	private static DatagramSocket udpclient;
	private static InetAddress sendaddr;
	private InetAddress myaddr;
	private int myudpport;
	static byte[] sendbuf;
	private static DatagramPacket handshakepacket;
	private P2PServer p2pServer;
	private boolean isRunning = true;
	private LinkedBlockingQueue<Document> requestQueue = new LinkedBlockingQueue<>();
	private ArrayList<HostPort> peers = new ArrayList<>();
	private UdpSenderHandler udpSenderHandler;
	ExecutorService service = Executors.newFixedThreadPool(3);
	public static CopyOnWriteArrayList<Socket> outgoingClients = new CopyOnWriteArrayList<>();
	public static CopyOnWriteArrayList<Document> udpclients = new CopyOnWriteArrayList<>();
	public Integer syncInterval;
	public static Map<String, DatagramPacket> packetMap = new HashMap<String, DatagramPacket>();


	
	public ServerMain() throws NumberFormatException, IOException, NoSuchAlgorithmException {
		fileSystemManager=new FileSystemManager(Configuration.getConfigurationValue("path"),this);
		mode = Configuration.getConfigurationValue(Constantkey.MODE_KEY);
		begin();
		start();
	}

	public void run() {
		syncInterval = Integer.parseInt(Configuration.getConfigurationValue("syncInterval"));
		while (true) {
			try {
				ArrayList<FileSystemEvent> events =fileSystemManager.generateSyncEvents();
				for(FileSystemEvent event: events) {
					processFileSystemEvent(event);
				}
				Thread.sleep(syncInterval * 1000);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * @author jianhongl
	 * @date 2019-05-30 15:10
	 * @description:
	*/
	public void begin(){
		if (mode.equals("tcp")){
			p2pServer = new P2PServer(fileSystemManager);
			service.submit(p2pServer);
			String host;
			int port;
			String peerstr = Configuration.getConfigurationValue("peers");
			String[] peerarray = peerstr.split(",");
			for (int i = 0; i < peerarray.length; i++) {
				String[] split = peerarray[i].split(":");
				host = split[0];
				port = Integer.parseInt(split[1]);
				HostPort hp = new HostPort(host, port);
				peers.add(hp);
			}

			try{
				ServerSocket clientSocket = new ServerSocket(Integer.parseInt(Configuration.getConfigurationValue("clientPort")));
				bitboxserver server = new bitboxserver(clientSocket);
			}catch (IOException e){
				e.printStackTrace();
			}

			for (HostPort hp : peers) {

				firstHandshake(hp);
			}
			new Thread(){
				BufferedWriter bw = null;
				public void run() {
					while (isRunning) {
						try {
							Document request = requestQueue.take();
							String command = request.getString(Constantkey.COMMAND_KEY);
							log.info("sending request" + command);
							for (int i = 0; i<outgoingClients.size();i++) {
								Socket client = outgoingClients.get(i);
								try {
									bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
									bw.write(request.toJson() + "\n");
									bw.flush();
								} catch (Exception e) {
									log.warning(e.getMessage());
								}
							}

							for (int i = 0; i<p2pServer.incomingClients.size();i++) {
								Socket client = p2pServer.incomingClients.get(i);
								try {
									bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
									bw.write(request.toJson() + "\n");
									bw.flush();
								} catch (Exception e) {
									log.warning(e.getMessage());
								}
							}

						} catch (InterruptedException e) {
							log.warning(e.getMessage());
						}
					}
				}
			}.start();
		}else {
			//this part is udp mode
			myudpport = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.UDPPORT_KEY));
			String host;
			int port;
			String peerstr = Configuration.getConfigurationValue("peers");
			String[] peerarray = peerstr.split(",");
			for (int i = 0; i < peerarray.length; i++) {
				String[] split = peerarray[i].split(":");
				host = split[0];
				port = Integer.parseInt(split[1]);
				HostPort hp = new HostPort(host, port);
				peers.add(hp);
			}
			try{
				//establish the udp datagramsocket
				udpclient = new DatagramSocket(myudpport);
				for(HostPort peer:peers){
					udphandshake(peer);
					UdpServer udpServer = new UdpServer(fileSystemManager,udpclient);
					udpServer.start();
				}
				//start the udp server and client

				// to monitor the file change
				udpSenderHandler = new UdpSenderHandler(udpclients,requestQueue,udpclient);
				udpSenderHandler.start();
				//try to start client
				ServerSocket clientSocket = new ServerSocket(Integer.parseInt(Configuration.getConfigurationValue("clientPort")));
				bitboxserver server = new bitboxserver(clientSocket);
			}catch (SocketException e){
				e.printStackTrace();
			}catch (IOException e){
				e.printStackTrace();
			}


		}

	}

	@Override
	public void processFileSystemEvent(FileSystemEvent fileSystemEvent) {
		switch (fileSystemEvent.event) {
			case FILE_CREATE:
				doCreateFileReq(fileSystemEvent);
				break;
			case FILE_DELETE:
				doDeleteFileReq(fileSystemEvent);
				break;
			case FILE_MODIFY:
				doModifyFileReq(fileSystemEvent);
				break;
			case DIRECTORY_CREATE:
				doCreateDirReq(fileSystemEvent);
				break;
			case DIRECTORY_DELETE:
				doDeleteDirReq(fileSystemEvent);
				break;
			default:
				log.warning("invalid event");
		}
	}

	public static String firstHandshake(HostPort hp) {
		BufferedWriter bw;
		BufferedReader br;
		String host = hp.host;
		int port = hp.port;
		System.out.println(host+":"+port);
		String command = null;
		try {
			client = new Socket(host,port);
			System.out.println("1111");
			bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
			br = new BufferedReader(new InputStreamReader(client.getInputStream()));

			Document message = new Document();
			message.append(Constantkey.COMMAND_KEY, Command.HANDSHAKE_REQUEST);
			Document hostport = new Document();
			hostport.append(Constantkey.HOST_KEY, Configuration.getConfigurationValue("advertisedName"));
			hostport.append(Constantkey.PORT_KEY, Integer.parseInt(Configuration.getConfigurationValue(Constantkey.PORT_KEY)));
			message.append(Constantkey.HOSTPORT_KEY, hostport);
			System.out.println(message.toJson());

			bw.write(message.toJson()+"\n");
			bw.flush();

			String response_str = br.readLine();
			Document response = Document.parse(response_str);

			command = response.getString(Constantkey.COMMAND_KEY);
			switch (command) {
				case Command.HANDSHAKE_RESPONSE:
					log.info("A client connected :"+client.getInetAddress().getHostName());
					outgoingClients.add(client);
					ReadThread rh = new ReadThread(br, fileSystemManager, client,mode);
					break;

				case Command.CONNECTION_REFUSED:
					ArrayList<HostPort> otherpeers = new ArrayList<>();
					ArrayList<Document> o = (ArrayList<Document>)response.get("peers");
					for(Document document :o) {
						HostPort hostp = new HostPort(document.getString(Constantkey.HOST_KEY), Integer.parseInt(document.getString(Constantkey.PORT_KEY)));
						otherpeers.add(hostp);
					}
					for(HostPort peer: otherpeers) {
						firstHandshake(peer);
					}
					break;
			}

		}catch (Exception e) {
			log.warning(e.toString());
		}

		return command;

	}

	public static void udphandshake(HostPort hp){
		String host = hp.host;
		int port = hp.port;
    	String command = null;
		try{
			sendaddr = InetAddress.getByName(host);
			Document message = new Document();
			Document hostport = new Document();
			hostport.append(Constantkey.HOST_KEY, Configuration.getConfigurationValue("advertisedName"));
			hostport.append(Constantkey.PORT_KEY, Integer.parseInt(Configuration.getConfigurationValue(Constantkey.UDPPORT_KEY)));
			message.append(Constantkey.HOSTPORT_KEY, hostport);
			message.append(Constantkey.COMMAND_KEY, Command.HANDSHAKE_REQUEST);
			System.out.println(message.toJson());
			sendbuf = message.toJson().getBytes();
			handshakepacket = new DatagramPacket(sendbuf,sendbuf.length,sendaddr,port);
			udpclient.send(handshakepacket);
			packetMap.put(Command.HANDSHAKE_RESPONSE+sendaddr.getHostAddress()+port,handshakepacket);
		}catch (Exception e){
			e.printStackTrace();
		}

	}

	private void doCreateDirReq(FileSystemEvent fileSystemEvent) {
		Document request = new Document();
		request.append(Constantkey.COMMAND_KEY, Command.DIRECTORY_CREATE_REQUEST);
		request.append(Constantkey.PATHNAME_KEY, fileSystemEvent.pathName);
		try {
			requestQueue.put(request);
		} catch (Exception e) {
			log.warning(e.getMessage());
		}
	}

	private void doDeleteDirReq(FileSystemEvent fileSystemEvent) {
		Document request = new Document();
		request.append(Constantkey.COMMAND_KEY,Command.DIRECTORY_DELETE_REQUEST);
		request.append(Constantkey.PATHNAME_KEY, fileSystemEvent.pathName);
		try {
			requestQueue.put(request);
		} catch (Exception e) {
			log.warning(e.getMessage());
		}
	}

	private void doCreateFileReq(FileSystemEvent fileSystemEvent) {
		Document request = new Document();
		Document fileDescriptorDoc = new Document();
		request.append(Constantkey.COMMAND_KEY, Command.FILE_CREATE_REQUEST);
		request.append(Constantkey.PATHNAME_KEY,fileSystemEvent.pathName );
		fileDescriptorDoc.append(Constantkey.MD5_KEY,fileSystemEvent.fileDescriptor.md5);
		fileDescriptorDoc.append(Constantkey.FILE_SIZE_KEY,fileSystemEvent.fileDescriptor.fileSize);
		fileDescriptorDoc.append(Constantkey.LAST_MODIFIED_KEY,fileSystemEvent.fileDescriptor.lastModified);
		request.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptorDoc);
		try {
			requestQueue.put(request);
		} catch (Exception e) {
			log.warning(e.getMessage());
		}
	}

	private void doDeleteFileReq(FileSystemEvent fileSystemEvent) {
		Document request = new Document();
		request.append(Constantkey.COMMAND_KEY, Command.FILE_DELETE_REQUEST);
		Document fileDescriptorDoc = new Document();
		fileDescriptorDoc.append(Constantkey.MD5_KEY,fileSystemEvent.fileDescriptor.md5);
		fileDescriptorDoc.append(Constantkey.FILE_SIZE_KEY,fileSystemEvent.fileDescriptor.fileSize);
		fileDescriptorDoc.append(Constantkey.LAST_MODIFIED_KEY,fileSystemEvent.fileDescriptor.lastModified);
		request.append(Constantkey.PATHNAME_KEY,fileSystemEvent.pathName);
		request.append(Constantkey.FILEDESCRIPTOR_KEY,fileDescriptorDoc);
		try {
			requestQueue.put(request);
		} catch (Exception e) {
			log.warning(e.getMessage());
		}
	}

	private void doModifyFileReq(FileSystemEvent fileSystemEvent) {
		Document request = new Document();
		Document fileDescriptorDoc = new Document();
		request.append(Constantkey.PATHNAME_KEY,fileSystemEvent.pathName);
		fileDescriptorDoc.append(Constantkey.MD5_KEY,fileSystemEvent.fileDescriptor.md5);
		fileDescriptorDoc.append(Constantkey.FILE_SIZE_KEY,fileSystemEvent.fileDescriptor.fileSize);
		fileDescriptorDoc.append(Constantkey.LAST_MODIFIED_KEY,fileSystemEvent.fileDescriptor.lastModified);
		request.append(Constantkey.FILEDESCRIPTOR_KEY,fileDescriptorDoc);
		request.append(Constantkey.COMMAND_KEY, Command.FILE_MODIFY_REQUEST);
		try {
			requestQueue.put(request);
		} catch (Exception e) {
			log.warning(e.getMessage());
		}
	}
//	private void udp_init(DatagramSocket datagramSocket,int myport){
//		try{
//			datagramSocket = new DatagramSocket(null);
//			InetAddress addr = InetAddress.getLocalHost();
//			String myhost = addr.getHostName().toString();
//			InetSocketAddress address = new InetSocketAddress(myhost,myport);
//			datagramSocket.bind(address);
//		}catch (Exception e){
//			e.printStackTrace();
//		}
//
//	}
}


