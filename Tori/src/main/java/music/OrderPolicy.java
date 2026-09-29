package music;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/** Pure guild queue and order-access rules shared by command handling and tests. */
final class OrderPolicy {
    private static final Set<String> TERMINAL=Set.of("DONE","CANCELLED");
    private OrderPolicy() {}
    static boolean active(String status) { return !TERMINAL.contains(status); }
    static boolean mayManageOrders(boolean manageServer,boolean administrator,String staffRoleId,Collection<String> memberRoleIds) {
        return manageServer||administrator||(staffRoleId!=null&&memberRoleIds.contains(staffRoleId));
    }
    static boolean validTransition(String from,String to) {
        return active(from)&&Set.of("NOTED","PROCESSING","DONE","CANCELLED").contains(to)&&!from.equals(to);
    }
    static boolean mayProcess(String mode,List<TicketOrderStore.Order> active,long orderId) {
        if("PARALLEL".equals(mode))return true;
        if(active.stream().anyMatch(order->order.id()!=orderId&&order.status().equals("PROCESSING")))return false;
        TicketOrderStore.Order selected=active.stream().filter(order->order.id()==orderId).findFirst().orElse(null);
        if(selected==null)return false;
        if(selected.status().equals("PROCESSING"))return true;
        TicketOrderStore.Order next=priorityOrder(active).stream().filter(order->order.status().equals("NOTED")).findFirst().orElse(null);
        return next!=null&&next.id()==orderId;
    }
    static List<TicketOrderStore.Order> priorityOrder(List<TicketOrderStore.Order> orders) {
        return orders.stream().sorted(Comparator
            .comparingInt((TicketOrderStore.Order order)->order.status().equals("PROCESSING")?0:1)
            .thenComparingInt(order->order.status().equals("PROCESSING")||order.fastpass()?0:1)
            .thenComparing(TicketOrderStore.Order::createdAt)
            .thenComparingLong(TicketOrderStore.Order::id)).toList();
    }
    static int position(long id,List<TicketOrderStore.Order> active) {
        for(int index=0;index<active.size();index++)if(active.get(index).id()==id)return index+1;
        return 0;
    }
}
