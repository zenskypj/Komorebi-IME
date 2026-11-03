#include "engine.h"
#include <iostream>
int main(int argc,char **argv) {
 if (argc != 3) return 2;
 try { Zenz32 engine(argv[1]); auto ticket=Zenz32::cancel(); auto output=engine.generate(argv[2],ticket); std::cout << output << std::endl; return output.empty() ? 1 : 0; }
 catch (const std::exception &e) { std::cerr << e.what() << std::endl; return 1; }
}
