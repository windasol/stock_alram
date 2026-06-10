package com.example.dart.notify;

import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;

public interface Notifier {
    void start();
    void sendBootMessage();
    void sendTitleAlert(Disclosure d, NewsFilter.TitleMatch match);
    void stop();
}
