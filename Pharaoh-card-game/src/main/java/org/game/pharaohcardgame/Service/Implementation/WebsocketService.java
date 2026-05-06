package org.game.pharaohcardgame.Service.Implementation;

import org.game.pharaohcardgame.Authentication.JwtService;
import org.game.pharaohcardgame.Exception.RoomNotFoundException;
import org.game.pharaohcardgame.Model.DTO.Request.SendMessageRequest;
import org.game.pharaohcardgame.Model.DTO.Response.MessageResponse;

import org.game.pharaohcardgame.Model.Room;
import org.game.pharaohcardgame.Model.User;
import org.game.pharaohcardgame.Repository.RoomRepository;
import org.game.pharaohcardgame.Repository.TokensRepository;
import org.game.pharaohcardgame.Repository.UserRepository;
import org.game.pharaohcardgame.Service.IWebsocketService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebsocketService implements IWebsocketService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final TokensRepository tokensRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuthenticationManager authenticationManager;


    @Override

    public String Greeting(String hello) {
        return "Hi " + hello + "!";
    }




}
