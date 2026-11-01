#pragma once
// The screen: a status bar, a home menu, and small apps. The recorder runs underneath whatever is shown.
namespace ui {
  void begin();
  void loop();        // call every loop(): reads the keyboard, redraws when needed
}
