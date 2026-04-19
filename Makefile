# Variables
JC = javac
# -g for debugging, -d src to put classes back into the src tree
JFLAGS = -g -d src -cp src
SRCDIR = src

# Find EVERY .java file in the src directory and its subdirectories
SOURCES := $(shell find $(SRCDIR) -name "*.java")

# Create a list of .class files to track for the 'all' target
CLASSES := $(SOURCES:.java=.class)

# Default target
all: $(CLASSES)

# The compilation rule
# This rule tells make: "To create a .class file, look for a .java file with the same name"
%.class: %.java
	$(JC) $(JFLAGS) $<

# Clean up all class files in the entire src tree
clean:
	find $(SRCDIR) -name "*.class" -exec rm -f {} +

# Phony targets to prevent conflicts with files named 'all' or 'clean'
.PHONY: all clean