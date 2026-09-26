package music;

import java.util.List;
import java.util.Set;

/** Pure guild queue rules shared by command handling and tests. */
final class OrderPolicy {
    private static final Set<String> TERMINAL=Set.of("DONE","CANCELLED");
    private OrderPolicy() {}
    static boolean active(String status) { return !TERMINAL.contains(status); }
    static boolean validTransition(String from,String to) {
        return active(from)&&Set.of("NOTED","PROCESSING","DONE","CANCELLED").contains(to)&&!from.equals(to);
    }
    static boolean mayProcess(String mode,List<TicketOrderStore.Order> active,long orderId) {
        if("PARALLEL".equals(mode))return true;
        return active.stream().noneMatch(order->order.id()!=orderId&&order.status().equals("PROCESSING"));
    }
    static int position(long id,List<TicketOrderStore.Order> active) {
        for(int index=0;index<active.size();index++)if(active.get(index).id()==id)return index+1;
        return 0;
    }
}
