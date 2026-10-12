package com.example.aamirror;

interface IShellService {
    // Bắt buộc theo Shizuku
    void destroy() = 16777114;

    boolean injectMotion(int displayId, int action, float x, float y, long downTime, long eventTime) = 1;
    boolean injectKey(int displayId, int keyCode) = 2;
    int exec(String command) = 3;
}
