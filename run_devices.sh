#!/bin/bash

# Initialize flags and values
s_flag=false
s_num=0
r_flag=false
r_num=0
t_flag=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s)
            s_flag=true
            shift
            if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
                s_num=$1
                shift
            else
                echo "Error: -s flag requires a number"
                exit 1
            fi
            ;;
        -r)
            r_flag=true
            shift
            if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
                r_num=$1
                shift
            else
                echo "Error: -r flag requires a number"
                exit 1
            fi
            ;;
        -t)
            t_flag=true
            shift
            ;;
        *)
            break
            ;;
    esac
done

# Run commands based on -s flag
if [ "$s_flag" == true ]; then
    for ((i=1; i<=s_num; i++)); do
        
        java -jar VirtualNetwork.jar -v s${i} > output/s${i}.log 2>&1 &
        echo "-----------------"
    done
fi

# Run commands based on -r flag
if [ "$r_flag" == true ]; then
    for ((i=1; i<=r_num; i++)); do
        echo "Running -r command attempt #$i with input: $r_num"
        if [ "$t_flag" == true ]; then
            java -jar VirtualNetwork.jar -v r${i} -r rtable.r${i} -a arp_cache > output/r${i}.log 2>&1 &
        else
            java -jar VirtualNetwork.jar -v r${i} -a arp_cache > output/r${i}.log 2>&1 &
            eval "$@ abc${r_num} 123" &
        fi
        echo "-----------------"
    done
    wait # Ensure background tasks complete before script exits
fi

