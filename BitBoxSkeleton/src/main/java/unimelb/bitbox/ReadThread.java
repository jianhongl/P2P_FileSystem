package unimelb.bitbox;

import unimelb.bitbox.util.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import static java.lang.Math.min;

public class ReadThread extends Thread {
    private static Logger log = Logger.getLogger(ReadThread.class.getName());
    public Socket client;
    private String mode;
    private BufferedReader br;
    private static FileSystemManager fileSystemManager;
    long blockSize = Long.parseLong(Configuration.getConfigurationValue("blockSize"));
    boolean isRunning = true;
    public CopyOnWriteArrayList<Socket> incomingClients = new CopyOnWriteArrayList<>();

    public ReadThread(
            BufferedReader br,
            FileSystemManager fileSystemManager,
            Socket client,
            String mode) {
        this.br = br;
        this.fileSystemManager = fileSystemManager;
        this.client = client;
        this.mode = mode;
        start();
    }

    public void run() {
        while(isRunning) {
            try {
                while (true) {
                    String request_str = br.readLine();
                    log.info("have a request" + request_str);
                    if (request_str == null) {
                        return;
                    }
                    Document request = Document.parse(request_str);
                    String command = request.getString(Constantkey.COMMAND_KEY);
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
                    switch (command) {
                        case Command.FILE_CREATE_REQUEST:
                            handlefile_create_request(request, bw);
                            break;
                        case Command.FILE_DELETE_REQUEST:
                            handlefile_delete_request(request, bw);
                            break;
                        case Command.FILE_MODIFY_REQUEST:
                            handlefile_modify_request(request, bw);
                            break;
                        case Command.DIRECTORY_CREATE_REQUEST:
                            handledirectory_create_request(request, bw);
                            break;
                        case Command.DIRECTORY_DELETE_REQUEST:
                            handledirectory_delete_request(request, bw);
                            break;
                        case Command.FILE_BYTES_REQUEST:
                            handlefile_bytes_request(request, bw);
                            break;
                        case Command.FILE_BYTES_RESPONSE:
                            handlefile_bytes_response(request, bw);
                            break;
                    }
                }
            } catch (Exception e) {
                log.warning(e.getMessage());
            }
        }
    }

	private void askforfilebytes(Document request, BufferedWriter bw) {
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        Document askfor = new Document();
        long size = fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY);
        try {
            if (!fileSystemManager.checkShortcut(pathName)) {
                long length;
                if (size <= blockSize) {
                    length = size;
                } else {
                    length = blockSize;
                }
                askfor.append(Constantkey.COMMAND_KEY, Command.FILE_BYTES_REQUEST);
                askfor.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
                askfor.append(Constantkey.PATHNAME_KEY,pathName);
                askfor.append(Constantkey.POSITION_KEY, 0);
                askfor.append(Constantkey.LENGTH_KEY, length);
                bw.write(askfor.toJson()+"\n");
                bw.flush();
            }
        } catch (NoSuchAlgorithmException e) {
            log.warning(e.toString());
        } catch (IOException e) {
            log.warning(e.toString());
        }
    }


    private void handlefile_bytes_response(Document request, BufferedWriter bw) {
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        long size = fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY);
        long position = request.getLong(Constantkey.POSITION_KEY);
        String content = request.getString(Constantkey.CONTENT_KEY);
        ByteBuffer byteBuffer = ByteBuffer.wrap(java.util.Base64.getDecoder().decode(content));
        try {
            Boolean write_file = fileSystemManager.writeFile(pathName,byteBuffer,position);
            log.info("write_file :"+write_file);
            if (!fileSystemManager.checkWriteComplete(pathName)) {
                long length;
                if (position + 2*blockSize <= size) {
                    length = blockSize;
                } else {
                    length = size +2*blockSize - position;
                }
                Document byteRequest = new Document();
                byteRequest.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
                byteRequest.append(Constantkey.COMMAND_KEY, Command.FILE_BYTES_REQUEST);
                byteRequest.append(Constantkey.PATHNAME_KEY,pathName);
                byteRequest.append(Constantkey.POSITION_KEY,position);
                byteRequest.append(Constantkey.LENGTH_KEY, length);
                bw.write(byteRequest.toJson()+"\n");
                bw.flush();
            } else {
                log.info("fileWritecomplete");
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warning(e.toString());
        }
    }

    private void handlefile_bytes_request(Document request, BufferedWriter bw) {
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        boolean status = true;
        String message = "successful read";
        String md5 = fileDescriptor.getString(Constantkey.MD5_KEY);

        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        long length = request.getLong(Constantkey.LENGTH_KEY);
        long position = request.getLong(Constantkey.POSITION_KEY);

        boolean exists = fileSystemManager.fileNameExists(pathName);

        if(!exists){
            message = "unsuccessful read";
            status = false;
        }
        ByteBuffer byteBuffer = null;
        try {
            byteBuffer = fileSystemManager.readFile(md5, position, length);
            String content = Base64.getEncoder().encodeToString(byteBuffer.array());
            System.out.println(content);
            Document response = new Document();
            response = appendResponse(response,fileDescriptor, pathName, status,  Command.FILE_BYTES_RESPONSE);
            response.append(Constantkey.POSITION_KEY,position);
            response.append(Constantkey.CONTENT_KEY,content);
            response.append(Constantkey.MESSAGE_KEY,message);
            response.append(Constantkey.LENGTH_KEY, length);
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            log.warning(e.toString());
        } catch (NoSuchAlgorithmException e) {
            log.warning(e.toString());
        }
    }

    private void handledirectory_delete_request(Document request, BufferedWriter bw) {
        boolean allright;
        boolean isSafe;
        boolean isExist;
        String message;
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        allright = fileSystemManager.deleteDirectory(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        isExist = fileSystemManager.dirNameExists(pathName);
        if(allright){
            message = "directory deleted";
        }else if(!isExist){
            message = "pathname does not exist";
        }else if(!isSafe) {
            message = "unsafe pathname given";
        }else {
            message = "there was a problem deleting the directory";
        }
        Document response = new Document();
        response = appendResponse(response,null, pathName, allright,  Command.DIRECTORY_DELETE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handledirectory_create_request(Document request, BufferedWriter bw) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        String message;
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        allright = fileSystemManager.makeDirectory(pathName);
        isExist = fileSystemManager.dirNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        if(allright){
            message = "directory created";
        }
        else if(isExist){
            message = "path name already exists";
        }
        else if(!isSafe){
            message = "unsafe pathname given";
        }else {
            message = "there was a problem creating the directory";
        }
        Document response = new Document();
        response = appendResponse(response,null, pathName, allright,  Command.DIRECTORY_CREATE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    private void handlefile_modify_request(Document request, BufferedWriter bw) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        boolean isModify;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        String md5 = fileDescriptor.getString(Constantkey.MD5_KEY);
        isExist = fileSystemManager.fileNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        isModify = fileSystemManager.fileNameExists(pathName,md5);
        String message;
        try {
            allright = fileSystemManager.modifyFileLoader(
                    pathName,
                    fileDescriptor.getString(Constantkey.MD5_KEY),
                    fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY)
            );
        } catch (Exception e) {
            allright = false;
        }

        Document response = new Document();
        response = appendResponse(response, fileDescriptor, pathName, allright, Command.FILE_MODIFY_RESPONSE);
        if(allright) {
            message = "file loader ready";
        }else if(!isExist){
            message = "pathname does not exist";
        }else  if(!isSafe){
            message = "unsafe pathname given";
        }else if(isModify){
            message = "file already exists with matching content";
        }else {
            message = "there was a problem modifying the file";
        }
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(allright)
            askforfilebytes(request,bw);
    }

    private void handlefile_delete_request(Document request, BufferedWriter bw) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        String message;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        isExist = fileSystemManager.dirNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        allright = fileSystemManager.deleteFile(
                pathName,
                fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY),
                fileDescriptor.getString(Constantkey.MD5_KEY));
        if(allright){
            message = "File deleted";
        }else if(!isSafe){
            message = "unsafe pathname given";
        }else if(!isExist){
            message = "pathname does not exist";
        }else {
            message = "there was a problem deleting the directory";
        }
        Document response = new Document();
        response = appendResponse(response, fileDescriptor, pathName, allright, Command.FILE_DELETE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handlefile_create_request(Document request, BufferedWriter bw) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        String message;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        isExist = fileSystemManager.fileNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        try {
            allright = fileSystemManager.createFileLoader(
                    pathName,
                    fileDescriptor.getString(Constantkey.MD5_KEY),
                    fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY),
                    fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY)
            );
        } catch (Exception e) {
            allright = false;
        }

        Document response = new Document();
        response = appendResponse(response,fileDescriptor, pathName, allright,  Command.FILE_CREATE_RESPONSE);
        if(allright) {
            message = "file loader ready";
        }else if(isExist) {
            message = "pathname already exists";
        }else if(!isSafe){
            message = "unsafe pathname given";
        }else {
            message = "there was a problem creating the file";
        }
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            bw.write(response.toJson()+"\n");
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(allright) {
            askforfilebytes(request,bw);
        }

    }
    private Document appendResponse(Document response,Document fileDescriptor, String pathName, boolean allright, String command) {
        response.append(Constantkey.COMMAND_KEY, command);
        if(fileDescriptor != null)
            response.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
        response.append(Constantkey.PATHNAME_KEY, pathName);
        response.append(Constantkey.STATUS_KEY, allright);
        return response;
    }
    
    private ArrayList<Document> connectedClients() {
        ArrayList<Document> connectedClients = new ArrayList<>();
        for (Socket client : incomingClients) {
            Document hostPort = new Document();
            hostPort.append(Constantkey.HOST_KEY, client.getInetAddress().getHostName());
            hostPort.append(Constantkey.PORT_KEY, client.getPort());
            connectedClients.add(hostPort);
        }
        return connectedClients;
    }
}
