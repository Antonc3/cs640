package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

class RIP {
  private Router router;
  private RouteTable routeTable;

  private final static int RIP_IP_ADDRESS = IPv4.toIPv4Address("224.0.0.9");
  private final static long TIMEOUT = 30000;

  RIP(final Router router) {
    this.router = router;
    this.routeTable = router.getRouteTable();

    // Add direct connections to route table
    router.getInterfaces().values().forEach(iface -> {
      int netmask = iface.getSubnetMask();
      int address = iface.getIpAddress();
      int network = netmask & address;
      routeTable.insert(network, 0, netmask, iface);
    });

    sendReq();

    new Thread(() -> {
      while (true) {
        try {
          sendRes();
          Thread.sleep(10000);
        } catch (InterruptedException e) {
        }
      }
    }).start();
  }

  private RIPv2 createRipPacket(boolean isResponse){
	RIPv2 packet = null;
  	if(isResponse){
    		packet = new RIPv2();
    		packet.setCommand(RIPv2.COMMAND_RESPONSE);
    
    		// Add all route entries to packet
    		for (RouteEntry entry : routeTable.getEntries()) {
      		packet.addEntry(new RIPv2Entry(
        		entry.getDestinationAddress(),
        		entry.getMaskAddress(),
        		entry.distance
     		));
    		}
    		return packet;
	}
	// Create request packet
	packet = new RIPv2();
	packet.setCommand(RIPv2.COMMAND_REQUEST);
	return packet;	
  }

  private UDP generateUdpPacket(RIPv2 ripPayload) {
    // Create UDP packet with RIP ports
    UDP udp = new UDP();
    udp.setSourcePort(UDP.RIP_PORT);
    udp.setDestinationPort(UDP.RIP_PORT);
    udp.setPayload(ripPayload);
    return udp;
  }

  private IPv4 generateIpPacket(Iface iface, UDP payload) {
    // Create IP packet with appropriate addressing
    IPv4 ip = new IPv4();
    ip.setTtl((byte)15);
    ip.setProtocol(IPv4.PROTOCOL_UDP);
    ip.setDestinationAddress(RIP_IP_ADDRESS);
    ip.setSourceAddress(iface.getIpAddress());
    ip.setPayload(payload);
    return ip;
  }

  private Ethernet generateEthernetPacket(Iface inIface, IPv4 payload) {
    // Create Ethernet frame with broadcast destination
    Ethernet ether = new Ethernet();
    ether.setEtherType(Ethernet.TYPE_IPv4);
    if(inIface.getMacAddress() == null){
	    if(router.FAIL_ON_NULL_MAC){
		    throw new NullPointerException("Iface " + inIface.getName()+ " has a null mac address");
	    }
	    ether.setSourceMACAddress("FF:FF:FF:FF:FF:FF");
    }
    else{
	    ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
    }
    ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
    ether.setPayload(payload);
    return ether;
  }

  private void sendReq() {
    // Create request packet
    RIPv2 ripPacket = createRipPacket(false);
    UDP udpPacket = generateUdpPacket(ripPacket);
    
    // Send on all interfaces
    for (Iface iface : router.getInterfaces().values()) {
      IPv4 ipPacket = generateIpPacket(iface, udpPacket);
      Ethernet ethernetPacket = generateEthernetPacket(iface, ipPacket);
      router.sendPacket(ethernetPacket, iface);
    }
  }

  private void sendRes() {
    // Build response packet stack
    RIPv2 ripPacket = createRipPacket(true);
    UDP udpPacket = generateUdpPacket(ripPacket);
    
    // Broadcast on all interfaces
    router.getInterfaces().values().forEach(iface -> {
      IPv4 ipPacket = generateIpPacket(iface, udpPacket);
      Ethernet ethernetPacket = generateEthernetPacket(iface, ipPacket);
      router.sendPacket(ethernetPacket, iface);
    });
  }

  private void handleReq(Ethernet etherPacket, Iface inIface) {
    // Extract source information from incoming packet
    IPv4 inIpPacket = (IPv4)etherPacket.getPayload();
    int sourceAddress = inIpPacket.getSourceAddress();
    byte[] sourceMac = etherPacket.getSourceMACAddress();
    
    // Create targeted response packet
    RIPv2 ripPacket = createRipPacket(true);
    UDP udpPacket = generateUdpPacket(ripPacket);
    
    // Create IP packet with unicast destination
    IPv4 ipPacket = generateIpPacket(inIface, udpPacket);
    ipPacket.setDestinationAddress(sourceAddress);
    
    // Create Ethernet packet with unicast destination
    Ethernet ethernetPacket = generateEthernetPacket(inIface, ipPacket);
    ethernetPacket.setDestinationMACAddress(sourceMac);
    
    // Send response
    router.sendPacket(ethernetPacket, inIface);
  }

  private void handleRes(IPv4 ipPacket, Iface inIface) {
    // Get RIP payload
    RIPv2 ripPacket = (RIPv2)ipPacket.getPayload().getPayload();
    int sourceAddress = ipPacket.getSourceAddress();
    boolean tableChanged = false;
    
    // Process each route entry
    for (RIPv2Entry entry : ripPacket.getEntries()) {
      int hopCount = entry.getMetric() + 1;
      int subnet = entry.getAddress() & entry.getSubnetMask();
      
      // Look for existing route
      RouteEntry existingRoute = routeTable.lookup(subnet);
      long currentTime = System.currentTimeMillis();
      long newExpiration = currentTime + TIMEOUT;
      
      if (existingRoute == null) {
        // Create new route entry
        RouteEntry newRoute = new RouteEntry(
            entry.getAddress(),
            sourceAddress,
            entry.getSubnetMask(), 
            inIface
        );
        newRoute.distance = hopCount;
        newRoute.recentValidTime = newExpiration;
        routeTable.insert(newRoute);
        tableChanged = true;
      } else {
        // Update existing route if better metric found
        if (existingRoute.distance > hopCount) {
          existingRoute.setGatewayAddress(sourceAddress);
          existingRoute.setInterface(inIface);
          existingRoute.distance = hopCount;
          tableChanged = true;
        }
        // Refresh route timeout regardless
        existingRoute.recentValidTime = Math.max(newExpiration, existingRoute.recentValidTime);
      }
    }
    
    // Propagate updates if routes changed
    if (tableChanged) {
      sendRes();
    }
  }

  boolean isRipPacket(IPv4 ipPacket) {
    boolean isRipIp = false;
    if(RIP_IP_ADDRESS == ipPacket.getDestinationAddress()){
    	isRipIp = true;
    }else{
    	isRipIp = router.getInterfaces().values().stream()
		    .anyMatch(iface -> ipPacket.getDestinationAddress() == iface.getIpAddress());
    }

    return isRipIp && 
           ipPacket.getProtocol() == IPv4.PROTOCOL_UDP &&
           ((UDP)ipPacket.getPayload()).getDestinationPort() == UDP.RIP_PORT;
  }

  void handlePacket(Ethernet etherPacket, Iface inIface) {
    // Extract RIP packet and determine command type
    IPv4 ipPacket = (IPv4)etherPacket.getPayload();
    RIPv2 ripPacket = (RIPv2)ipPacket.getPayload().getPayload();
    int command = ripPacket.getCommand();
    
    // Handle based on command type
    if (command == RIPv2.COMMAND_REQUEST) {
      handleReq(etherPacket, inIface);
    } else if (command == RIPv2.COMMAND_RESPONSE) {
      handleRes(ipPacket, inIface);
    }
  }
}
