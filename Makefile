JC = javac
JFLAGS = -g -d src -cp src
SRCDIR = src

SOURCES := $(shell find $(SRCDIR) -name "*.java")

all: $(SOURCES)
	$(JC) $(JFLAGS) $(SOURCES)

clean:
	find $(SRCDIR) -name "*.class" -exec rm -f {} +

.PHONY: all clean