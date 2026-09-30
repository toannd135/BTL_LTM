package socket;

import java.io.*;
import java.net.Socket;
import java.net.ServerSocket;

public class serverTCP {
    public static void main(String argv[]) throws Exception {
        String sentence_from_client;
        String sentence_to_client;

        ServerSocket welcomeSocket = new ServerSocket(6543);
        while (true) {
            Socket connectionSocket = welcomeSocket.accept();
            BufferedReader inFromClient = new BufferedReader(
                    new InputStreamReader(connectionSocket.getInputStream()));
            DataOutputStream outToClient = new DataOutputStream(
                    new DataOutputStream(connectionSocket.getOutputStream()));
            sentence_from_client = inFromClient.readLine();
            sentence_to_client = sentence_from_client + "Server accepted \n";
            outToClient.writeBytes(sentence_to_client);
            return;
        }
    }
}