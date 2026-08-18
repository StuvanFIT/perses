#include <stdio.h>

int unused_helper(int x) {
  int t = x * 2;
  return t + 1;
}

int another_unused(void) {
  int a = 1;
  int b = 2;
  return a + b;
}

int main(void) {
  int keep_me = 42;
  int noise_one = 7;
  int noise_two = 13;
  printf("%d\n", keep_me);
  return 0;
}
