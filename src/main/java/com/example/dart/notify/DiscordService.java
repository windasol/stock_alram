package com.example.dart.notify;

import com.example.dart.model.Disclosure;
import com.example.dart.service.DocumentService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordService extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordService.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final String botToken;
    private final String channelId;
    private final DocumentService documentService;
    private JDA jda;

    public DiscordService(String botToken, String channelId, DocumentService documentService) {
        this.botToken = botToken;
        this.channelId = channelId;
        this.documentService = documentService;
    }

    public void start() {
        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(this)
                    .build()
                    .awaitReady();
            log.info("Discord 봇 연결 완료");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Discord 봇 시작 실패", e);
        }
    }

    public void sendBootMessage() {
        TextChannel channel = getChannel();
        if (channel == null) return;
        channel.sendMessage("DART 호재 알림 봇이 시작되었습니다.").queue();
    }

    public void sendTitleAlert(Disclosure d) {
        TextChannel channel = getChannel();
        if (channel == null) return;

        String text = String.format("**[호재 공시]**\n**회사명**: %s\n**공시제목**: %s\n**접수일**: %s\n**제출인**: %s",
                d.corpName(), d.reportNm(), d.rceptDt(), d.flrNm());

        String dartUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + d.rceptNo();

        channel.sendMessage(text)
                .addActionRow(
                        Button.primary("detail:" + d.rceptNo(), "📄 상세 내역 보기"),
                        Button.link(dartUrl, "🌐 DART 원문")
                )
                .queue(
                        success -> log.info("알림 전송 완료: {} - {}", d.corpName(), d.reportNm()),
                        failure -> log.error("알림 전송 실패: {}", d.corpName(), failure)
                );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (!componentId.startsWith("detail:")) return;

        String rceptNo = componentId.substring("detail:".length());
        event.deferReply().queue();

        try {
            String detail = documentService.buildDetail(rceptNo);
            String message = truncate(detail);
            event.getHook().sendMessage(message).queue();
        } catch (Exception e) {
            log.error("상세 내역 조회 실패: {}", rceptNo, e);
            event.getHook().sendMessage("상세 내역을 불러오는 데 실패했습니다.").queue();
        }
    }

    public void stop() {
        if (jda != null) {
            jda.shutdown();
            log.info("Discord 봇 종료");
        }
    }

    private TextChannel getChannel() {
        if (jda == null) return null;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            log.error("디스코드 채널을 찾을 수 없음: {}", channelId);
        }
        return channel;
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_MESSAGE_LENGTH) return text;
        return text.substring(0, MAX_MESSAGE_LENGTH - 20) + "\n...(생략)";
    }
}
