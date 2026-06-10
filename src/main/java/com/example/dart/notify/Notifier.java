package com.example.dart.notify;

import com.example.dart.model.Disclosure;

public interface Notifier {
    void start();
    void sendBootMessage();
    void sendTitleAlert(Disclosure d);
    void stop();
}
